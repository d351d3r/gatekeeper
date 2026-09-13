// ILoadIconCallback.aidl
package io.gatekeeper.services;

import android.graphics.Bitmap;

interface ILoadIconCallback {
    void callback(in Bitmap icon);
}
