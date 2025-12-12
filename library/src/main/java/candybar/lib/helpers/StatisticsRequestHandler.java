package candybar.lib.helpers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.danimahardhika.android.helpers.core.utils.LogUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
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

        if (token == null || token.isEmpty()) {
            return "Statistics service token not configured";
        }

        if (endpoint == null || endpoint.isEmpty()) {
            return "Statistics service endpoint not configured";
        }

        OkHttpClient client = new OkHttpClient();

        try {
            // Step 1: Upload app info in batches
            String appInfoError = uploadAppInfoInBatches(client, requests, endpoint, token);
            if (appInfoError != null) {
                return appInfoError;
            }

            // Step 2: Upload icons for each request
            String iconUploadError = uploadIcons(client, requests, endpoint, token);
            if (iconUploadError != null) {
                return iconUploadError;
            }

            return null; // Success
        } catch (Exception e) {
            LogUtil.e(Log.getStackTraceString(e));
            return "Request failed: " + e.getMessage();
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
