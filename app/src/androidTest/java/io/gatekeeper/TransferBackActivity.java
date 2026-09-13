package io.gatekeeper;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.util.Log;

/**
 * Обратный ход F1 точкой входа, которая поднимает холодный процесс.
 *
 * Ресивер {@link TransferBackReceiver} для этого не годится: манифестный ресивер
 * тестового APK по {@code am broadcast} в холодном процессе не стартует, что и
 * записано граблями в плане. Activity стартует всегда:
 *
 *   adb shell am start --user &lt;work&gt; -n io.gatekeeper.test/io.gatekeeper.TransferBackActivity
 *
 * Это ровно тот случай, ради которого сценарий и существует: Gatekeeper в этот
 * момент переустановлен с другой подписью, инструментация к нему уже не
 * прицепится, и вернуть владение может только сам компаньон.
 *
 * На Java намеренно: процесс тестового APK живёт без Kotlin-рантайма.
 */
public class TransferBackActivity extends Activity {
    private static final String TAG = "TransferBack";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        ComponentName source = new ComponentName(this, TransferTargetAdminReceiver.class);
        ComponentName target = new ComponentName(
                "io.gatekeeper", "io.gatekeeper.receivers.GatekeeperDeviceAdminReceiver");
        boolean owner = dpm.isProfileOwnerApp(getPackageName());
        Log.i(TAG, "activity transfer back, owner=" + owner);
        if (owner) {
            PersistableBundle bundle = new PersistableBundle();
            bundle.putString("marker", "back-to-gatekeeper");
            try {
                dpm.transferOwnership(source, target, bundle);
                Log.i(TAG, "transfer back OK, still owner=" + dpm.isProfileOwnerApp(getPackageName()));
            } catch (Exception e) {
                Log.e(TAG, "transfer back FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        } else {
            Log.w(TAG, "not a profile owner, nothing to transfer");
        }
        finish();
    }
}
