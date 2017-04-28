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
import android.support.annotation.AnyThread;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.WorkerThread;

import com.google.android.gms.common.api.Scope;
import com.google.android.gms.drive.Drive;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Abstract class for simple interaction with files on Google Drive. Its main goal is to simplify and unify backup,
 * sync, store and restore operations. This class is not targeted for browsing or searching files on the drive.
 * <br>
 * After {@code GoogleDrive} object created it should be initialized by calling {@link #init(Context)}.
 * Then any of the methods may be called, they should call {@link #connect()} internally.
 * <br>
 * You should call static method {@link #setEnabled(boolean)} passing true before doing work, otherwise {@link #connect()}
 * will fails. Note that the flag may by cleared internally if {@link #connect()} requires user action such as authorization.
 */
public abstract class GoogleDrive {
    /** Message to be thrown if {@link #connect()} called while {@link #isEnabled()} returns {@code false} */
    public static final String ERROR_NOT_ENABLED = "Not enabled";

    /** Global enable flag */
    private static boolean enabled;

    /** Drive scope to be used */
    Scope mScope = Drive.SCOPE_APPFOLDER;

    /** If this set to {@code false}, {@link #connect()} function should throw an exception */
    public static void setEnabled(boolean enabled) { GoogleDrive.enabled = enabled; }
    /** Check if enabled */
    public static boolean isEnabled() { return enabled; }

    /** Set drive scope */
    public void setScope(Scope scope) { mScope = scope; }

    /**
     * Make all {@link Context} dependent initializations.
     * This function doesn't check {@link #isEnabled()}
     */
    @AnyThread
    abstract public void init(Context context);

    /**
     * Perform connection operations. <br>
     * This function may be called by the client app, but actually, call to any function that requires connection
     * must call through this method.<br>
     * This method returns immediately if already connected
     *
     * @throws IOException if something was wrong
     */
    @WorkerThread
    abstract public void connect() throws IOException;

    /**
     * Clean up the object. This function should be called if the object doesn't needed any more
     */
    abstract public void destroy();

    /**
     * Change working directory.<br>
     * Sets the folder to be used to {@link #ls()} and to create files with {@link #write(String, String, String, InputStream)}
     * and {@link #review(String, String, String)} methods.<br>
     * If {@code id != null} it will be used. Otherwise {@code path} will be searched and recreated if needed and last path
     * segments id will be returned.
     *
     * @param id drive folder id or null
     * @param path path to scan/recreate
     * @return id from param or result folder id
     * @throws IOException on error
     */
    @WorkerThread @NonNull
    abstract public String cd(String id, String path) throws IOException;

    /**
     * @return list of ids in working folder
     * @throws IOException on error
     */
    @WorkerThread @NonNull
    abstract public List<String> ls() throws IOException;

    /**
     * Write file pointed by {@code id}. If {@code id == null} or file with given {@code id} doesn't exists it will be
     * created with given {@code title} and {@code mimeType}.
     *
     * @param id file id or null
     * @param title name of file to create
     * @param mimeType type of file to create
     * @param inputStream data to be written to the file
     * @return id of the file that was written
     * @throws IOException on error
     */
    @WorkerThread @NonNull
    abstract public String write(String id, String title, String mimeType, InputStream inputStream) throws IOException;

    /**
     * Check if file pointed by {@code id} exists. Create file if {@code id == null} or missed
     *
     * @param id file id to check or null
     * @param title name of file to create
     * @param mimeType type of file to create
     * @return valid file id
     * @throws IOException on error
     */
    @WorkerThread @NonNull
    abstract public String review(String id, String title, String mimeType) throws IOException;

    /**
     * Finish file modification
     * @param id file id
     * @throws IOException on error
     */
    @WorkerThread
    abstract public void commit(String id) throws IOException;

    abstract public void close(String id) throws IOException;

    /**
     * Get an input stream from a file on the drive. The stream should be closed by the caller
     *
     * @param id file id
     * @return input stream
     * @throws IOException on error
     */
    @WorkerThread @NonNull
    abstract public InputStream openInputStream(String id) throws IOException;

    /**
     * Get an output stream to a file on the drive. The stream should be closed by the caller
     *
     * @param id file id
     * @return output stream
     * @throws IOException on error
     */
    @WorkerThread @NonNull
    abstract public OutputStream openOutputStream(String id) throws IOException;

    /**
     * Returns the time that the file denoted by {@code id} was last modified
     *
     * @param id file id
     * @return A long value representing the time the file was last modified, measured in milliseconds
     * since the epoch (00:00:00 GMT, January 1, 1970)
     * @throws IOException on error
     */
    @WorkerThread
    abstract public long lastModified(String id) throws IOException;

    /**
     * Deletes the file or directory denoted by {@code id}
     *
     * @param id file id
     * @throws IOException on error
     */
    @WorkerThread
    abstract public void delete(String id) throws IOException;

    /**
     * Call this method from {@link Activity#onActivityResult(int, int, Intent)} to process results requested by this
     *
     * @param activity activity context
     * @param requestCode see {@link Activity#onActivityResult(int, int, Intent)}
     * @param resultCode see {@link Activity#onActivityResult(int, int, Intent)}
     * @param data see {@link Activity#onActivityResult(int, int, Intent)}
     * @return true if request code was recognized by this
     */
    @MainThread
    abstract public boolean activityResultCallback(Activity activity, int requestCode, int resultCode, Intent data);

    /**
     * An interface to be notified on async events
     */
    public interface Client {
        /**
         * Called on successful {@link #connect()}
         */
        void googleDriveConnected();
        //void googleDriveError(String message);
    }
}
