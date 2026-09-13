// IFileShuttleServiceCallback.aidl
package io.gatekeeper.services;

import io.gatekeeper.services.IFileShuttleService;

interface IFileShuttleServiceCallback {
    void callback(in IFileShuttleService service);
}
