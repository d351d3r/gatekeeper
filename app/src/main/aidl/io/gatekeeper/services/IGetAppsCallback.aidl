// IGetAppsCallback.aidl
package io.gatekeeper.services;

import io.gatekeeper.util.ApplicationInfoWrapper;

interface IGetAppsCallback {
    void callback(in List<ApplicationInfoWrapper> apps);
}
