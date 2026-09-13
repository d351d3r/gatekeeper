package io.gatekeeper;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
import android.util.Log;

/**
 * Обратный ход F1: вернуть владение профилем Gatekeeper после передачи компаньону.
 * Выполняется от лица io.gatekeeper.test, то есть от текущего владельца профиля,
 * поэтому дёргается бродкастом снаружи:
 *
 *   adb shell am broadcast --user <work> -f 0x00000020 \
 *     -n io.gatekeeper.test/io.gatekeeper.TransferBackReceiver \
 *     -a io.gatekeeper.test.TRANSFER_BACK
 *
 * На Java намеренно: ресивер поднимается в собственном процессе тестового APK,
 * где нет Kotlin-рантайма (он приходит из основного APK только при инструментации).
 */
public class TransferBackReceiver extends BroadcastReceiver {
    private static final String TAG = "TransferBack";

    @Override
    public void onReceive(Context context, Intent intent) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        ComponentName source = new ComponentName(context, TransferTargetAdminReceiver.class);
        ComponentName target = new ComponentName(
                "io.gatekeeper", "io.gatekeeper.receivers.GatekeeperDeviceAdminReceiver");
        boolean owner = dpm.isProfileOwnerApp(context.getPackageName());
        Log.i(TAG, "transfer back requested, owner=" + owner + " source=" + source + " target=" + target);
        if (!owner) {
            Log.w(TAG, "not a profile owner, nothing to transfer");
            return;
        }
        PersistableBundle bundle = new PersistableBundle();
        bundle.putString("marker", "back-to-gatekeeper");
        try {
            dpm.transferOwnership(source, target, bundle);
            Log.i(TAG, "transfer back OK, still owner=" + dpm.isProfileOwnerApp(context.getPackageName()));
        } catch (Exception e) {
            Log.e(TAG, "transfer back FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
