package io.gatekeeper;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.util.Log;

/**
 * Цель передачи владения профилем в сценарии F1. Живёт только в androidTest APK
 * (io.gatekeeper.test) и играет роль «Owner Companion»: отдельный пакет с
 * отдельной подписью, объявивший support-transfer-ownership.
 *
 * На Java: класс инстанцируется системой в процессе тестового APK, где нет
 * Kotlin-рантайма.
 */
public class TransferTargetAdminReceiver extends DeviceAdminReceiver {
    private static final String TAG = "TransferTarget";

    @Override
    public void onTransferOwnershipComplete(Context context, PersistableBundle bundle) {
        super.onTransferOwnershipComplete(context, bundle);
        Log.i(TAG, "ownership received, marker=" + (bundle == null ? null : bundle.getString("marker")));
    }

    @Override
    public void onTransferAffiliatedProfileOwnershipComplete(Context context, UserHandle user) {
        super.onTransferAffiliatedProfileOwnershipComplete(context, user);
        Log.i(TAG, "affiliated profile ownership received for " + user);
    }
}
