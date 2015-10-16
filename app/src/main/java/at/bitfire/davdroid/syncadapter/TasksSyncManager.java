/*
 * Copyright © 2013 – 2015 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package at.bitfire.davdroid.syncadapter;

import android.accounts.Account;
import android.content.ContentValues;
import android.content.Context;
import android.content.SyncResult;
import android.os.Bundle;
import android.text.TextUtils;

import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.ResponseBody;

import org.apache.commons.codec.Charsets;
import org.apache.commons.lang3.StringUtils;
import org.dmfs.provider.tasks.TaskContract.TaskLists;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import at.bitfire.dav4android.DavCalendar;
import at.bitfire.dav4android.DavResource;
import at.bitfire.dav4android.exception.DavException;
import at.bitfire.dav4android.exception.HttpException;
import at.bitfire.dav4android.property.CalendarColor;
import at.bitfire.dav4android.property.CalendarData;
import at.bitfire.dav4android.property.DisplayName;
import at.bitfire.dav4android.property.GetCTag;
import at.bitfire.dav4android.property.GetContentType;
import at.bitfire.dav4android.property.GetETag;
import at.bitfire.davdroid.ArrayUtils;
import at.bitfire.davdroid.Constants;
import at.bitfire.davdroid.R;
import at.bitfire.davdroid.resource.LocalCalendar;
import at.bitfire.davdroid.resource.LocalResource;
import at.bitfire.davdroid.resource.LocalTask;
import at.bitfire.davdroid.resource.LocalTaskList;
import at.bitfire.ical4android.CalendarStorageException;
import at.bitfire.ical4android.InvalidCalendarException;
import at.bitfire.ical4android.Task;
import at.bitfire.ical4android.TaskProvider;
import lombok.Cleanup;

public class TasksSyncManager extends SyncManager {

    protected static final int MAX_MULTIGET = 30;

    final protected TaskProvider provider;


    public TasksSyncManager(Context context, Account account, Bundle extras, TaskProvider provider, SyncResult result, LocalTaskList taskList) {
        super(Constants.NOTIFICATION_TASK_SYNC, context, account, extras, result);
        this.provider = provider;
        localCollection = taskList;
    }

    @Override
    protected String getSyncErrorTitle() {
        return context.getString(R.string.sync_error_tasks, account.name);
    }


    @Override
    protected void prepare() {
        Thread.currentThread().setContextClassLoader(context.getClassLoader());     // required for ical4j

        collectionURL = HttpUrl.parse(localTaskList().getSyncId());
        davCollection = new DavCalendar(httpClient, collectionURL);
    }

    @Override
    protected void queryCapabilities() throws DavException, IOException, HttpException, CalendarStorageException {
        davCollection.propfind(0, DisplayName.NAME, CalendarColor.NAME, GetCTag.NAME);

        // update name and color
        DisplayName pDisplayName = (DisplayName)davCollection.properties.get(DisplayName.NAME);
        String displayName = (pDisplayName != null && !TextUtils.isEmpty(pDisplayName.displayName)) ?
                pDisplayName.displayName : collectionURL.toString();

        CalendarColor pColor = (CalendarColor)davCollection.properties.get(CalendarColor.NAME);
        int color = (pColor != null && pColor.color != null) ? pColor.color : LocalCalendar.defaultColor;

        ContentValues values = new ContentValues(2);
        Constants.log.info("Setting new task list name \"" + displayName + "\" and color 0x" + Integer.toHexString(color));
        values.put(TaskLists.LIST_NAME, displayName);
        values.put(TaskLists.LIST_COLOR, color);
        localTaskList().update(values);
    }

    @Override
    protected RequestBody prepareUpload(LocalResource resource) throws IOException, CalendarStorageException {
        LocalTask local = (LocalTask)resource;
        return RequestBody.create(
                DavCalendar.MIME_ICALENDAR,
                local.getTask().toStream().toByteArray()
        );
    }

    @Override
    protected void listRemote() throws IOException, HttpException, DavException {
        // fetch list of remote VTODOs and build hash table to index file name
        davCalendar().calendarQuery("VTODO");
        remoteResources = new HashMap<>(davCollection.members.size());
        for (DavResource vCard : davCollection.members) {
            String fileName = vCard.fileName();
            Constants.log.debug("Found remote VTODO: " + fileName);
            remoteResources.put(fileName, vCard);
        }
    }

    @Override
    protected void downloadRemote() throws IOException, HttpException, DavException, CalendarStorageException {
        Constants.log.info("Downloading " + toDownload.size() + " tasks (" + MAX_MULTIGET + " at once)");

        // download new/updated iCalendars from server
        for (DavResource[] bunch : ArrayUtils.partition(toDownload.toArray(new DavResource[toDownload.size()]), MAX_MULTIGET)) {
            Constants.log.info("Downloading " + StringUtils.join(bunch, ", "));

            if (bunch.length == 1) {
                // only one contact, use GET
                DavResource remote = bunch[0];

                ResponseBody body = remote.get("text/calendar");
                String eTag = ((GetETag)remote.properties.get(GetETag.NAME)).eTag;

                @Cleanup InputStream stream = body.byteStream();
                processVTodo(remote.fileName(), eTag, stream, body.contentType().charset(Charsets.UTF_8));

            } else {
                // multiple contacts, use multi-get
                List<HttpUrl> urls = new LinkedList<>();
                for (DavResource remote : bunch)
                    urls.add(remote.location);
                davCalendar().multiget(urls.toArray(new HttpUrl[urls.size()]));

                // process multiget results
                for (DavResource remote : davCollection.members) {
                    String eTag;
                    GetETag getETag = (GetETag)remote.properties.get(GetETag.NAME);
                    if (getETag != null)
                        eTag = getETag.eTag;
                    else
                        throw new DavException("Received multi-get response without ETag");

                    Charset charset = Charsets.UTF_8;
                    GetContentType getContentType = (GetContentType)remote.properties.get(GetContentType.NAME);
                    if (getContentType != null && getContentType.type != null) {
                        MediaType type = MediaType.parse(getContentType.type);
                        if (type != null)
                            charset = type.charset(Charsets.UTF_8);
                    }

                    CalendarData calendarData = (CalendarData)remote.properties.get(CalendarData.NAME);
                    if (calendarData == null || calendarData.iCalendar == null)
                        throw new DavException("Received multi-get response without address data");

                    @Cleanup InputStream stream = new ByteArrayInputStream(calendarData.iCalendar.getBytes());
                    processVTodo(remote.fileName(), eTag, stream, charset);
                }
            }
        }
    }


    // helpers

    private LocalTaskList localTaskList() { return ((LocalTaskList)localCollection); }
    private DavCalendar davCalendar() { return (DavCalendar)davCollection; }

    private void processVTodo(String fileName, String eTag, InputStream stream, Charset charset) throws IOException, CalendarStorageException {
        Task[] tasks = null;
        try {
            tasks = Task.fromStream(stream, charset);
        } catch (InvalidCalendarException e) {
            Constants.log.error("Received invalid iCalendar, ignoring", e);
            return;
        }

        if (tasks != null && tasks.length == 1) {
            Task newData = tasks[0];

            // update local task, if it exists
            LocalTask localTask = (LocalTask)localResources.get(fileName);
            if (localTask != null) {
                Constants.log.info("Updating " + fileName + " in local tasklist");
                localTask.setETag(eTag);
                localTask.update(newData);
                syncResult.stats.numUpdates++;
            } else {
                Constants.log.info("Adding " + fileName + " to local task list");
                localTask = new LocalTask(localTaskList(), newData, fileName, eTag);
                localTask.add();
                syncResult.stats.numInserts++;
            }
        } else
            Constants.log.error("Received VCALENDAR with not exactly one VTODO; ignoring " + fileName);
    }

}