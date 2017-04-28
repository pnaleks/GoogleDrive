/*
 *  Copyright 2016 P.N.Alekseev <pnaleks@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ru.pnapp.googledrive;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.WorkerThread;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.drive.DriveApi;
import com.google.android.gms.drive.DriveContents;
import com.google.android.gms.drive.DriveFile;
import com.google.android.gms.drive.DriveFolder;
import com.google.android.gms.drive.DriveId;
import com.google.android.gms.drive.DriveResource;
import com.google.android.gms.drive.Metadata;
import com.google.android.gms.drive.MetadataBuffer;
import com.google.android.gms.drive.MetadataChangeSet;
import com.google.android.gms.drive.query.Filters;
import com.google.android.gms.drive.query.Query;
import com.google.android.gms.drive.query.SearchableField;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GoogleDriveAndroid extends GoogleDrive {
    private static final int REQUEST_RESOLUTION = 0x7301;

    private GoogleApiClient mGoogleApiClient;
    private DriveFolder mFolder;
    private Context mContext;

    private Map<String, DriveContents> mDriveContentsMap = Collections.synchronizedMap(new HashMap<String, DriveContents>());

    @Override
    public void init(Context context) {
        mContext = context;

        mGoogleApiClient = new GoogleApiClient.Builder(context)
                .addApi(Drive.API)
                .addScope(mScope)
                .build();
    }

    @Override
    public void destroy() {
        for (DriveContents contents : mDriveContentsMap.values()) {
            contents.discard(mGoogleApiClient);
        }
        if (mGoogleApiClient.isConnected())  mGoogleApiClient.disconnect();
    }

    @WorkerThread
    synchronized public void connect() throws IOException {
        if (!isEnabled()) throw new IOException(ERROR_NOT_ENABLED);

        if (mGoogleApiClient.isConnected()) return;

        ConnectionResult result = mGoogleApiClient.blockingConnect();

        if (result.isSuccess()) {
            if (mFolder == null) {

                if (mScope == Drive.SCOPE_APPFOLDER) {
                    mFolder = Drive.DriveApi.getAppFolder(mGoogleApiClient);
                } else {
                    mFolder = Drive.DriveApi.getRootFolder(mGoogleApiClient);
                }

                if (mContext instanceof Client && mContext instanceof Activity) {
                    ((Activity) mContext).runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            ((Client) mContext).googleDriveConnected();
                        }
                    });
                }

            }
            return;
        }

        setEnabled(false);

        if (mContext instanceof Activity) {
            if (result.hasResolution()) {
                try {
                    result.startResolutionForResult((Activity) mContext, REQUEST_RESOLUTION);
                } catch (IntentSender.SendIntentException ignore) {}
            } else {
                final int errorCode = result.getErrorCode();
                ((Activity) mContext).runOnUiThread(
                        new Runnable() {
                            @Override
                            public void run() {
                                GoogleApiAvailability.getInstance().getErrorDialog((Activity) mContext, errorCode, 0).show();
                            }
                        }
                );

            }
        }

        throw new IOException("Not authorized");
    }

    @Override @NonNull
    public String cd(String id, String path) throws IOException {
        connect();

        if (id != null) {
            DriveId driveId = getDriveId(id);
            mFolder = driveId.asDriveFolder();
            return id;
        }

        if (path == null) throw new FileNotFoundException();

        DriveFolder folder;
        if (mScope.equals(Drive.SCOPE_FILE)) {
            folder = Drive.DriveApi.getRootFolder(mGoogleApiClient);
        } else {
            folder = Drive.DriveApi.getAppFolder(mGoogleApiClient);
        }

        Uri uri = Uri.parse(path);
        boolean seek = true;
        for (String segment : uri.getPathSegments()) {
            if (seek) {
                DriveFolder found = findFolder(folder, segment);
                if (found != null) {
                    folder = found;
                    continue;
                }
                seek = false;
            }
            folder = createFolder(folder, segment);
            if (folder == null) {
                break;
            }
        }

        if (folder == null) throw new FileNotFoundException("Unable to initialize " + path);

        mFolder = folder;
        return getResourceId(folder.getDriveId());
    }

    @Override @NonNull
    public List<String> ls() throws IOException {
        connect();

        ArrayList<String> resultList = new ArrayList<>();

        MetadataBuffer metadataBuffer = mFolder.listChildren(mGoogleApiClient).await().getMetadataBuffer();

        if (metadataBuffer != null) {
            for (Metadata metadata : metadataBuffer) {
                resultList.add(getResourceId(metadata.getDriveId()));
            }
        }

        return resultList;
    }

    @Override @NonNull
    public String write(String id, String title, String mimeType, InputStream inputStream) throws IOException {
        connect();

        id = review(id, title, mimeType);

        OutputStream outputStream = openOutputStream(id);

        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
        commit(id);

        return id;
    }

    @Override @NonNull
    public String review(String id, String title, String mimeType) throws IOException {
        connect();

        DriveId driveId = (id == null) ? null : getDriveId(id);

        if (driveId != null) {
            DriveResource.MetadataResult result = driveId.asDriveResource().getMetadata(mGoogleApiClient).await();
            if (result.getStatus().isSuccess()) {
                return id;
            }
        }

        MetadataChangeSet changeSet = new MetadataChangeSet.Builder()
                .setTitle(title)
                .setMimeType(mimeType)
                .build();

        DriveFolder.DriveFileResult result = mFolder.createFile(mGoogleApiClient, changeSet, null).await();

        if (result.getStatus().isSuccess()) {
            driveId = result.getDriveFile().getDriveId();

            return getResourceId(driveId);
        }

        throw new IOException(result.getStatus().getStatusMessage());
    }

    @Override
    public void commit(String id) throws IOException {
        DriveContents contents = mDriveContentsMap.get(id);
        if (contents == null) throw new IOException("Unexpected commit on " + id);
        try {
            Status status = contents.commit(mGoogleApiClient, null).await();
            if (!status.isSuccess()) throw new IOException(status.getStatusMessage());
        } finally {
            mDriveContentsMap.remove(id);
        }
    }

    @Override
    public void close(String id) throws IOException {
        DriveContents contents = mDriveContentsMap.get(id);
        if (contents == null) throw new IOException("Unexpected commit on " + id);
        contents.discard(mGoogleApiClient);
    }

    @Override @NonNull
    public InputStream openInputStream(String id) throws IOException {
        connect();

        DriveId driveId = getDriveId(id);

        if (mDriveContentsMap.containsKey(id)) throw new IOException("Resource busy");

        DriveApi.DriveContentsResult result = driveId.asDriveFile()
                .open(mGoogleApiClient, DriveFile.MODE_READ_ONLY, null).await();


        if (result.getStatus().isSuccess()) {
            DriveContents contents = result.getDriveContents();
            if (contents != null) {
                InputStream inputStream = contents.getInputStream();
                if (inputStream != null) {
                    mDriveContentsMap.put(id, contents);
                    return inputStream;
                }
            }
        }

        throw new IOException(result.getStatus().getStatusMessage());
    }

    @Override @NonNull
    public OutputStream openOutputStream(String id) throws IOException {
        connect();

        DriveId driveId = getDriveId(id);

        if (mDriveContentsMap.containsKey(id)) throw new IOException("Resource busy");

        DriveApi.DriveContentsResult result = driveId.asDriveFile()
                .open(mGoogleApiClient, DriveFile.MODE_WRITE_ONLY, null).await();

        if (result.getStatus().isSuccess()) {
            DriveContents contents = result.getDriveContents();
            if (contents != null) {
                OutputStream outputStream = contents.getOutputStream();
                if (outputStream != null) {
                    mDriveContentsMap.put(id, contents);
                    return outputStream;
                }
            }
        }

        throw new IOException(result.getStatus().getStatusMessage());
    }

    @Override
    public long lastModified(String id) throws IOException {
        connect();

        DriveId driveId = getDriveId(id);

        DriveResource.MetadataResult result = driveId.asDriveResource().getMetadata(mGoogleApiClient).await();
        if (result.getStatus().isSuccess()) return result.getMetadata().getModifiedDate().getTime();

        throw new IOException(result.getStatus().getStatusMessage());
    }

    @Override
    public void delete(String id) throws IOException {
        connect();
        DriveId driveId = getDriveId(id);
        Status status = driveId.asDriveResource().delete(mGoogleApiClient).await();
        if (!status.isSuccess()) throw new IOException(status.getStatusMessage());
    }

    @Override
    public boolean activityResultCallback(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_RESOLUTION) {
            if (resultCode == Activity.RESULT_OK) setEnabled(true);
            return true;
        }
        return false;
    }

    @WorkerThread @NonNull
    private DriveId getDriveId(String resourceId) throws IOException {
        connect();

        DriveApi.DriveIdResult result = Drive.DriveApi.fetchDriveId(mGoogleApiClient, resourceId).await();
        if (result.getStatus().isSuccess()) {
            DriveId driveId = result.getDriveId();
            if (driveId != null) {
                return driveId;
            }
        }
        throw new FileNotFoundException(result.getStatus().getStatusMessage());
    }

    private static final Object lock = new Object();
    @WorkerThread @NonNull
    private String getResourceId(DriveId driveId) throws IOException {
        String resourceId = driveId.getResourceId();

        int count = 0;
        while (resourceId == null && count++ < 10) {
            synchronized (lock) {try { lock.wait(200); } catch (InterruptedException e) { e.printStackTrace();}}
            MetadataChangeSet changeSet = new MetadataChangeSet.Builder().build();
            DriveResource.MetadataResult result = driveId.asDriveResource().updateMetadata(mGoogleApiClient, changeSet).await();
            if (result.getStatus().isSuccess()) {
                resourceId = result.getMetadata().getDriveId().getResourceId();
            } else {
                throw new IOException(result.getStatus().getStatusMessage());
            }
        }

        if (resourceId == null) throw new IOException("Resource id not found");

        return resourceId;
    }

    /**
     * Проверяет наличие папки title в родительской папке parent.
     * @param parent родительская папка
     * @param title искомая папка
     * @return Первый {@link DriveFolder} с именем title или null если не найдена
     */
    @WorkerThread
    private DriveFolder findFolder(DriveFolder parent, String title) {
        Query query = new Query.Builder()
                .addFilter(Filters.eq(SearchableField.TITLE, title))
                .addFilter(Filters.eq(SearchableField.TRASHED, false))
                .build();
        MetadataBuffer metadataBuffer = parent.queryChildren(mGoogleApiClient, query).await().getMetadataBuffer();
        DriveFolder result = null;
        if (metadataBuffer != null) {
            for (Metadata metadata : metadataBuffer) {
                if (metadata.isFolder() && !metadata.isTrashed()) {
                    result = metadata.getDriveId().asDriveFolder();
                    break;
                }
            }
            metadataBuffer.release();
        }
        return result;
    }

    /**
     * Создает папку title в каталоге parent
     * @param parent родительская папка
     * @param title имя новой папки
     * @return новая папка или null если произошла ошибка
     */
    @WorkerThread
    private DriveFolder createFolder(DriveFolder parent, String title) {
        MetadataChangeSet changeSet = new MetadataChangeSet.Builder()
                .setTitle(title)
                .build();
        DriveFolder.DriveFolderResult driveFolderResult = parent.createFolder(mGoogleApiClient, changeSet).await();
        if (driveFolderResult.getStatus().isSuccess()) {
            return driveFolderResult.getDriveFolder();
        }
        return null;
    }
}
