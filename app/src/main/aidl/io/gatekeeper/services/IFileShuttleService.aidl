// IFileShuttleService.aidl
package io.gatekeeper.services;

import android.graphics.Point;
import android.os.ParcelFileDescriptor;
import io.gatekeeper.util.ShuttleEntry;

interface IFileShuttleService {
    // oneway on purpose, see FileShuttleConnection.pingTask
    oneway void ping();
    // Типизированные записи вместо List/Map: нетипизированный ответ разбирался
    // устаревшими readArrayList/readHashMap с десериализацией любых Serializable.
    List<ShuttleEntry> loadFiles(String path);
    ShuttleEntry loadFileMeta(String path);
    ParcelFileDescriptor openFile(String path, String mode);
    ParcelFileDescriptor openThumbnail(String path, in Point sizeHint);
    String createFile(String path, String mimeType, String displayName);
    String deleteFile(String path);
    boolean isChildOf(String parentPath, String childPath);
}
