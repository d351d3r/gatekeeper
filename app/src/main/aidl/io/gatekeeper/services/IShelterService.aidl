// IShelterService.aidl
package io.gatekeeper.services;

import android.content.pm.ApplicationInfo;

import io.gatekeeper.services.IAppInstallCallback;
import io.gatekeeper.services.IGetAppsCallback;
import io.gatekeeper.services.ILoadIconCallback;
import io.gatekeeper.services.IStartActivityProxy;
import io.gatekeeper.util.ApplicationInfoWrapper;
import io.gatekeeper.util.UriForwardProxy;

interface IShelterService {
    void ping();
    void stopShelterService(boolean kill);
    void getApps(IGetAppsCallback callback, boolean showAll);
    void loadIcon(in ApplicationInfoWrapper info, ILoadIconCallback callback);
    void installApp(in ApplicationInfoWrapper app, IAppInstallCallback callback);
    void installApk(in UriForwardProxy uri, IAppInstallCallback callback);
    void uninstallApp(in ApplicationInfoWrapper app, IAppInstallCallback callback);
    void freezeApp(in ApplicationInfoWrapper app);
    void unfreezeApp(in ApplicationInfoWrapper app);
    boolean hasUsageStatsPermission();
    boolean hasSystemAlertPermission();
    boolean hasAllFileAccessPermission();
    List<String> getCrossProfileWidgetProviders();
    boolean setCrossProfileWidgetProviderEnabled(String pkgName, boolean enabled);
    void setStartActivityProxy(in IStartActivityProxy proxy);
    List<String> getCrossProfilePackages();
    void setCrossProfilePackages(in List<String> packages);
    // Новые методы дописываются только в конец: номер транзакции задается порядком.
    boolean isDefaultNetworkTunneled();
}
