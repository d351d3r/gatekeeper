package io.gatekeeper.services;
import android.os.ParcelFileDescriptor;

// Only Gatekeeper's appId may transact. IDs are local MediaStore IDs, never paths.
interface IMediaExchange {
    boolean isWorkProfile();
    String configuration();
    String identity();
    long latestId();
    List<String> images(long afterId, int limit, String configuration);
    String describe(long id, String configuration);
    ParcelFileDescriptor open(long id, String version, String configuration);
    void connect(IMediaExchange peer);
    oneway void changed();
    String status();
}
