// IUriOpener.aidl
package io.gatekeeper.util;

import android.os.ParcelFileDescriptor;

interface IUriOpener {
    ParcelFileDescriptor openFile(in String mode);
}
