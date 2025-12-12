package candybar.lib.helpers;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.danimahardhika.android.helpers.core.FileHelper;
import com.danimahardhika.android.helpers.core.utils.LogUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import candybar.lib.R;
import candybar.lib.applications.CandyBarApplication;
import candybar.lib.items.Request;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;

/*
 * CandyBar - Material Dashboard
 *
 * Statistics Request Handler for uploading icon requests to online service.
 * Also generates ZIP file and shows share intent for traditional icon request flow.
 */

public class StatisticsRequestHandler implements CandyBarApplication.Configuration.IconRequestHandler {

    private static final int BATCH_SIZE = 10;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType PNG = MediaType.parse("image/png");

    private final Context mContext;

    public StatisticsRequestHandler(@NonNull Context context) {
        this.mContext = context.getApplicationContext();
    }

    @Override
    public String submit(List<Request> requests, boolean isPremium) {
        // First try to get from XML resources, then fall back to Configuration
        String endpoint = mContext.getResources().getString(R.string.statistics_service_endpoint);
        String token = mContext.getResources().getString(R.string.statistics_service_token);

        // Fall back to Configuration if XML values are empty
        if (endpoint == null || endpoint.isEmpty()) {
            endpoint = CandyBarApplication.getConfiguration().getStatisticsServiceEndpoint();
        }
        if (token == null || token.isEmpty()) {
            token = CandyBarApplication.getConfiguration().getStatisticsServiceToken();
        }

        // Step 1: Upload statistics to server (fire and forget - don't fail if this fails)
        if (token != null && !token.isEmpty() && endpoint != null && !endpoint.isEmpty()) {
            try {
                OkHttpClient client = new OkHttpClient();
                uploadAppInfoInBatches(client, requests, endpoint, token);
                uploadIcons(client, requests, endpoint, token);
            } catch (Exception e) {
                // Log but don't fail - statistics upload is optional
                LogUtil.e("Statistics upload failed: " + e.getMessage());
            }
        }

        // Step 2: Generate ZIP file with icons and XML files
        try {
            String zipError = generateZipFile(requests);
            if (zipError != null) {
                return zipError;
            }
        } catch (Exception e) {
            LogUtil.e(Log.getStackTraceString(e));
            return "Failed to generate ZIP file: " + e.getMessage();
        }

        // Step 3: Show share intent on main thread
        new Handler(Looper.getMainLooper()).post(() -> showShareIntent());

        return null; // Success
    }

    @Nullable
    private String generateZipFile(List<Request> requests) {
        try {
            File cacheDir = mContext.getCacheDir();
            List<String> files = new ArrayList<>();

            // Save icons to cache directory
            for (Request request : requests) {
                Drawable drawable = DrawableHelper.getPackageIcon(mContext, request.getActivity());
                if (drawable != null) {
                    String fileName = RequestHelper.fixNameForRequest(request.getName()) + ".png";
                    File iconFile = new File(cacheDir, fileName);
                    if (IconsHelper.saveIcon(files, iconFile, drawable)) {
                        request.setFileName(fileName);
                    }
                }
            }

            // Generate XML files
            File appFilter = RequestHelper.buildXml(mContext, requests, RequestHelper.XmlType.APPFILTER);
            File appMap = RequestHelper.buildXml(mContext, requests, RequestHelper.XmlType.APPMAP);
            File themeResources = RequestHelper.buildXml(mContext, requests, RequestHelper.XmlType.THEME_RESOURCES);

            if (appFilter != null) files.add(appFilter.toString());
            if (appMap != null) files.add(appMap.toString());
            if (themeResources != null) files.add(themeResources.toString());

            // Create ZIP file
            CandyBarApplication.sZipPath = FileHelper.createZip(files, new File(cacheDir,
                    RequestHelper.getGeneratedZipName(RequestHelper.ZIP)));

            return null; // Success
        } catch (Exception e) {
            LogUtil.e(Log.getStackTraceString(e));
            return "Failed to generate ZIP: " + e.getMessage();
        }
    }

    private void showShareIntent() {
        if (CandyBarApplication.sZipPath == null) {
            LogUtil.e("ZIP path is null, cannot show share intent");
            return;
        }

        File zip = new File(CandyBarApplication.sZipPath);
        if (!zip.exists()) {
            LogUtil.e("ZIP file does not exist: " + CandyBarApplication.sZipPath);
            return;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/zip");

            Uri uri = FileHelper.getUriFromFile(mContext, mContext.getPackageName(), zip);
            if (uri == null) uri = Uri.fromFile(zip);

            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            String appName = mContext.getResources().getString(R.string.app_name);
            intent.putExtra(Intent.EXTRA_SUBJECT, appName + " Icon Request");

            Intent chooser = Intent.createChooser(intent, mContext.getString(R.string.share));
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(chooser);
        } catch (Exception e) {
            LogUtil.e("Failed to show share intent: " + e.getMessage());
        }
    }

    @Nullable
    private String uploadAppInfoInBatches(OkHttpClient client, List<Request> requests, String endpoint, String token) {
        // Split requests into batches of BATCH_SIZE
        List<List<Request>> batches = new ArrayList<>();
        for (int i = 0; i < requests.size(); i += BATCH_SIZE) {
            batches.add(requests.subList(i, Math.min(i + BATCH_SIZE, requests.size())));
        }

        for (List<Request> batch : batches) {
            String error = uploadAppInfoBatch(client, batch, endpoint, token);
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    @Nullable
    private String uploadAppInfoBatch(OkHttpClient client, List<Request> batch, String endpoint, String token) {
        try {
            JSONArray jsonArray = new JSONArray();
            String languageCode = Locale.getDefault().getLanguage();

            for (Request request : batch) {
                JSONObject appInfo = new JSONObject();
                appInfo.put("languageCode", languageCode);
                appInfo.put("mainActivity", request.getActivity());
                appInfo.put("localizedName", request.getName());
                appInfo.put("defaultName", request.getName());
                appInfo.put("packageName", request.getPackageName());
                jsonArray.put(appInfo);
            }

            RequestBody body = RequestBody.create(jsonArray.toString(), JSON);
            okhttp3.Request httpRequest = new okhttp3.Request.Builder()
                    .url(endpoint + "/app-info/create")
                    .addHeader("Authorization", "Bearer " + token)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(body)
                    .build();

            try (Response response = client.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    return "Failed to upload app info: HTTP " + response.code();
                }
            }
            return null;
        } catch (Exception e) {
            LogUtil.e(Log.getStackTraceString(e));
            return "Failed to upload app info: " + e.getMessage();
        }
    }

    @Nullable
    private String uploadIcons(OkHttpClient client, List<Request> requests, String endpoint, String token) {
        for (Request request : requests) {
            String error = uploadIcon(client, request, endpoint, token);
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    @Nullable
    private String uploadIcon(OkHttpClient client, Request request, String endpoint, String token) {
        try {
            // Step 1: Get signed upload URL
            String uploadUrl = getSignedUploadUrl(client, request.getPackageName(), endpoint, token);
            if (uploadUrl == null) {
                return "Failed to get upload URL for " + request.getPackageName();
            }

            // Step 2: Get icon as PNG bytes
            Drawable drawable = DrawableHelper.getPackageIcon(mContext, request.getActivity());
            if (drawable == null) {
                return "Failed to get icon for " + request.getPackageName();
            }

            Bitmap bitmap = DrawableHelper.toBitmap(drawable);
            if (bitmap == null) {
                return "Failed to convert icon to bitmap for " + request.getPackageName();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            byte[] pngBytes = baos.toByteArray();

            // Step 3: Upload to S3
            RequestBody body = RequestBody.create(pngBytes, PNG);
            okhttp3.Request uploadRequest = new okhttp3.Request.Builder()
                    .url(uploadUrl)
                    .put(body)
                    .build();

            try (Response response = client.newCall(uploadRequest).execute()) {
                if (!response.isSuccessful()) {
                    return "Failed to upload icon for " + request.getPackageName() + ": HTTP " + response.code();
                }
            }
            return null;
        } catch (Exception e) {
            LogUtil.e(Log.getStackTraceString(e));
            return "Failed to upload icon for " + request.getPackageName() + ": " + e.getMessage();
        }
    }

    @Nullable
    private String getSignedUploadUrl(OkHttpClient client, String packageName, String endpoint, String token) {
        try {
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(endpoint + "/app-icon/generate-upload-url?packageName=" + packageName)
                    .addHeader("Authorization", "Bearer " + token)
                    .addHeader("Accept", "*/*")
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    LogUtil.e("Failed to get upload URL: HTTP " + response.code());
                    return null;
                }
                String responseBody = response.body().string();
                JSONObject json = new JSONObject(responseBody);
                return json.getString("uploadURL");
            }
        } catch (Exception e) {
            LogUtil.e(Log.getStackTraceString(e));
            return null;
        }
    }
}
