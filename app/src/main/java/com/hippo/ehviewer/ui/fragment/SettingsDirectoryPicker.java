/*
 * Copyright 2026 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui.fragment;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.ui.DirPickerActivity;
import com.hippo.unifile.UniFile;
import com.hippo.util.ExceptionUtils;

final class SettingsDirectoryPicker {

    private SettingsDirectoryPicker() {
    }

    static void selectLocation(@NonNull Fragment fragment,
                               @Nullable UniFile currentLocation,
                               int directoryRequestCode,
                               int documentRequestCode) {
        int sdk = Build.VERSION.SDK_INT;
        if (sdk < Build.VERSION_CODES.KITKAT) {
            openDirectoryPicker(fragment, currentLocation, directoryRequestCode);
        } else if (sdk < Build.VERSION_CODES.LOLLIPOP) {
            new AlertDialog.Builder(fragment.requireActivity())
                    .setMessage(R.string.settings_download_pick_dir_kk)
                    .setPositiveButton(R.string.settings_download_continue,
                            (dialog, which) -> openDirectoryPicker(fragment,
                                    currentLocation, directoryRequestCode))
                    .show();
        } else {
            DialogInterface.OnClickListener listener = (dialog, which) -> {
                switch (which) {
                    case DialogInterface.BUTTON_POSITIVE:
                        openDirectoryPicker(fragment, currentLocation,
                                directoryRequestCode);
                        break;
                    case DialogInterface.BUTTON_NEUTRAL:
                        openDocumentTree(fragment, documentRequestCode);
                        break;
                    default:
                        break;
                }
            };
            new AlertDialog.Builder(fragment.requireActivity())
                    .setMessage(R.string.settings_download_pick_dir_l)
                    .setPositiveButton(R.string.settings_download_continue, listener)
                    .setNeutralButton(R.string.settings_download_document, listener)
                    .show();
        }
    }

    static boolean handleActivityResult(@NonNull Fragment fragment,
                                        int requestCode,
                                        int resultCode,
                                        @Nullable Intent data,
                                        int directoryRequestCode,
                                        int documentRequestCode,
                                        @NonNull OnDirectoryPickedListener listener) {
        if (requestCode != directoryRequestCode && requestCode != documentRequestCode) {
            return false;
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            return true;
        }

        Uri uri = data.getData();
        if (uri == null) {
            showInvalidDirectory(fragment);
            return true;
        }

        UniFile directory;
        if (requestCode == documentRequestCode) {
            try {
                fragment.requireActivity().getContentResolver()
                        .takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (RuntimeException e) {
                showInvalidDirectory(fragment);
                return true;
            }
            directory = UniFile.fromTreeUri(fragment.requireContext(), uri);
        } else {
            directory = UniFile.fromUri(fragment.requireContext(), uri);
        }

        if (directory != null) {
            listener.onDirectoryPicked(directory);
        } else {
            showInvalidDirectory(fragment);
        }
        return true;
    }

    private static void openDirectoryPicker(@NonNull Fragment fragment,
                                            @Nullable UniFile currentLocation,
                                            int requestCode) {
        Intent intent = new Intent(fragment.requireContext(), DirPickerActivity.class);
        if (currentLocation != null) {
            intent.putExtra(DirPickerActivity.KEY_FILE_URI, currentLocation.getUri());
        }
        fragment.startActivityForResult(intent, requestCode);
    }

    private static void openDocumentTree(@NonNull Fragment fragment, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            fragment.startActivityForResult(intent, requestCode);
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            Toast.makeText(fragment.getActivity(), R.string.error_cant_find_activity,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private static void showInvalidDirectory(@NonNull Fragment fragment) {
        Toast.makeText(fragment.getActivity(),
                R.string.settings_download_cant_get_download_location,
                Toast.LENGTH_SHORT).show();
    }

    interface OnDirectoryPickedListener {
        void onDirectoryPicked(@NonNull UniFile directory);
    }
}
