package nl.xservices.plugins;

import nl.xservices.plugins.*;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.Activity;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.LabeledIntent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.text.Html;
import android.util.Base64;
import android.view.Gravity;
import android.widget.Toast;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SocialSharing extends CordovaPlugin {

  private static final String ACTION_AVAILABLE_EVENT = "available";
  private static final String ACTION_SHARE_EVENT = "share";
  private static final String ACTION_SHARE_WITH_OPTIONS_EVENT = "shareWithOptions";

  private static final int ACTIVITY_CODE_SEND__BOOLRESULT = 1;
  private static final int ACTIVITY_CODE_SEND__OBJECT = 2;

  private CallbackContext _callbackContext;

  private String pasteMessage;

  private abstract class SocialSharingRunnable implements Runnable {
    public CallbackContext callbackContext;

    SocialSharingRunnable(CallbackContext cb) {
      this.callbackContext = cb;
    }
  }

  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
    this._callbackContext = callbackContext; // only used for onActivityResult
    this.pasteMessage = null;
    if (ACTION_AVAILABLE_EVENT.equals(action)) {
      callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK));
      return true;
    } else if (ACTION_SHARE_WITH_OPTIONS_EVENT.equals(action)) {
      return shareWithOptions(callbackContext, args.getJSONObject(0));
    } else {
      callbackContext.error("socialSharing." + action + " is not a supported function. Did you mean '" + ACTION_SHARE_EVENT + "'?");
      return false;
    }
  }

  private String getDownloadDir() throws IOException {
    // better check, otherwise it may crash the app
    if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
      // we need to use external storage since we need to share to another app
      final String dir = webView.getContext().getExternalFilesDir(null) + "/socialsharing-downloads";
      createOrCleanDir(dir);
      return dir;
    } else {
      return null;
    }
  }

  private boolean shareWithOptions(CallbackContext callbackContext, JSONObject jsonObject) {
    return doSendIntent(
        callbackContext,
        jsonObject.optString("message", null),
        jsonObject.optString("subject", null),
        jsonObject.optJSONArray("files") == null ? new JSONArray() : jsonObject.optJSONArray("files"),
        jsonObject.optString("url", null),
        jsonObject.optString("appPackageName", null),
        jsonObject.optString("chooserTitle", null),
        false,
        false
    );
  }

  private boolean doSendIntent(
      final CallbackContext callbackContext,
      final String msg,
      final String subject,
      final JSONArray files,
      final String url,
      final String appPackageName,
      final String chooserTitle,
      final boolean peek,
      final boolean boolResult) {
    return doSendIntent(callbackContext, msg, subject, files, url, appPackageName, chooserTitle, peek, boolResult, null);
  }

  private boolean doSendIntent(
      final CallbackContext callbackContext,
      final String msg,
      final String subject,
      final JSONArray files,
      final String url,
      final String appPackageName,
      final String chooserTitle,
      final boolean peek,
      final boolean boolResult,
      final String appName) {

    final CordovaInterface mycordova = cordova;
    final CordovaPlugin plugin = this;

    cordova.getThreadPool().execute(new SocialSharingRunnable(callbackContext) {
      public void run() {
        String message = msg;
        final boolean hasMultipleAttachments = files.length() > 1;
        final Intent sendIntent = new Intent(hasMultipleAttachments ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);
        final Intent receiverIntent = new Intent(cordova.getActivity().getApplicationContext(), ShareChooserPendingIntent.class);
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(cordova.getActivity().getApplicationContext(), 0, receiverIntent, PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        sendIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);

        try {
          if (files.length() > 0 && !"".equals(files.getString(0))) {
            final String dir = getDownloadDir();
            if (dir != null) {
              ArrayList<Uri> fileUris = new ArrayList<Uri>();
              Uri fileUri = null;
              for (int i = 0; i < files.length(); i++) {
                fileUri = getFileUriAndSetType(sendIntent, dir, files.getString(i), subject, i);
                fileUri = FileProvider.getUriForFile(webView.getContext(), cordova.getActivity().getPackageName()+".sharing.provider", new File(fileUri.getPath()));
                fileUris.add(fileUri);
              }
              if (!fileUris.isEmpty()) {
                if (hasMultipleAttachments) {
                  sendIntent.putExtra(Intent.EXTRA_STREAM, fileUris);
                } else {
                  sendIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
                }
              }
            } else {
              sendIntent.setType("text/plain");
            }
          } else {
            sendIntent.setType("text/plain");
          }
        } catch (Exception e) {
          callbackContext.error(e.getMessage());
        }

        if (notEmpty(subject)) {
          sendIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
        }

        // add the URL to the message, as there seems to be no separate field
        if (notEmpty(url)) {
          if (notEmpty(message)) {
            message += " " + url;
          } else {
            message = url;
          }
        }
        if (notEmpty(message)) {
          sendIntent.putExtra(android.content.Intent.EXTRA_TEXT, message);
          // sometimes required when the user picks share via sms
          if (Build.VERSION.SDK_INT < 21) { // LOLLIPOP
            sendIntent.putExtra("sms_body", message);
          }
        }

        // this was added to start the intent in a new window as suggested in #300 to prevent crashes upon return
        sendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

          if (peek) {
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK));
          } else {
            // experimenting a bit
            // as an experiment for #300 we're explicitly running it on the ui thread here
            cordova.getActivity().runOnUiThread(new Runnable() {
              public void run() {
                Intent chooseIntent;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                  // Intent.createChooser's third param was only added in SDK version 22.
                  chooseIntent = Intent.createChooser(sendIntent, chooserTitle, pendingIntent.getIntentSender());
                } else {
                  chooseIntent = Intent.createChooser(sendIntent, chooserTitle);
                }
                ComponentName componentName = chooseIntent.resolveActivity(cordova.getActivity().getPackageManager());
                if (componentName.getPackageName().equals("android") && componentName.getClassName().equals("com.android.internal.app.ChooserActivity")) {
                  mycordova.startActivityForResult(plugin, chooseIntent, boolResult ? ACTIVITY_CODE_SEND__BOOLRESULT : ACTIVITY_CODE_SEND__OBJECT);
                }
              }
            });
          }
      }
    });
    return true;
  }

  @SuppressLint("NewApi")
  private void copyHintToClipboard(String msg, String label) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
      return;
    }
    final ClipboardManager clipboard = (android.content.ClipboardManager) cordova.getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
    final ClipData clip = android.content.ClipData.newPlainText(label, msg);
    clipboard.setPrimaryClip(clip);
  }

  @SuppressLint("NewApi")
  private void showPasteMessage(String label) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
      return;
    }
    final Toast toast = Toast.makeText(webView.getContext(), label, Toast.LENGTH_LONG);
    toast.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL, 0, 0);
    toast.show();
  }

  private Uri getFileUriAndSetType(Intent sendIntent, String dir, String image, String subject, int nthFile) throws IOException {
    // we're assuming an image, but this can be any filetype you like
    String localImage = image;
    if (image.endsWith("mp4") || image.endsWith("mov") || image.endsWith("3gp")){
      sendIntent.setType("video/*");
    } else if (image.endsWith("mp3")) {
      sendIntent.setType("audio/x-mpeg");
    } else {
      sendIntent.setType("image/*");
    }

    if (image.startsWith("http") || image.startsWith("www/")) {
      String filename = getFileName(image);
      localImage = "file://" + dir + "/" + filename.replaceAll("[^a-zA-Z0-9._-]", "");
      if (image.startsWith("http")) {
        // filename optimisation taken from https://github.com/EddyVerbruggen/SocialSharing-PhoneGap-Plugin/pull/56
        URLConnection connection = new URL(image).openConnection();
        String disposition = connection.getHeaderField("Content-Disposition");
        if (disposition != null) {
          final Pattern dispositionPattern = Pattern.compile("filename=([^;]+)");
          Matcher matcher = dispositionPattern.matcher(disposition);
          if (matcher.find()) {
            filename = matcher.group(1).replaceAll("[^a-zA-Z0-9._-]", "");
            if (filename.length() == 0) {
              // in this case we can't determine a filetype so some targets (gmail) may not render it correctly
              filename = "file";
            }
            localImage = "file://" + dir + "/" + filename;
          }
        }
        saveFile(getBytes(connection.getInputStream()), dir, filename);
        // update file type
        String fileType = getMIMEType(image);
        sendIntent.setType(fileType);
      } else {
        saveFile(getBytes(webView.getContext().getAssets().open(image)), dir, filename);
      }
    } else if (image.startsWith("data:")) {
      // safeguard for https://code.google.com/p/android/issues/detail?id=7901#c43
      if (!image.contains(";base64,")) {
        sendIntent.setType("text/plain");
        return null;
      }
      // image looks like this: data:image/png;base64,R0lGODlhDAA...
      final String encodedImg = image.substring(image.indexOf(";base64,") + 8);
      // correct the intent type if anything else was passed, like a pdf: data:application/pdf;base64,..
      if (!image.contains("data:image/")) {
        sendIntent.setType(image.substring(image.indexOf("data:") + 5, image.indexOf(";base64")));
      }
      // the filename needs a valid extension, so it renders correctly in target apps
      final String imgExtension = image.substring(image.indexOf("/") + 1, image.indexOf(";base64"));
      String fileName;
      // if a subject was passed, use it as the filename
      // filenames must be unique when passing in multiple files [#158]
      if (notEmpty(subject)) {
        fileName = sanitizeFilename(subject) + (nthFile == 0 ? "" : "_" + nthFile) + "." + imgExtension;
      } else {
        fileName = "file" + (nthFile == 0 ? "" : "_" + nthFile) + "." + imgExtension;
      }
      saveFile(Base64.decode(encodedImg, Base64.DEFAULT), dir, fileName);
      localImage = "file://" + dir + "/" + fileName;
    } else if (image.startsWith("df:")) {
      // safeguard for https://code.google.com/p/android/issues/detail?id=7901#c43
      if (!image.contains(";base64,")) {
        sendIntent.setType("text/plain");
        return null;
      }
      // format looks like this :  df:filename.txt;data:image/png;base64,R0lGODlhDAA...
      final String fileName = image.substring(image.indexOf("df:") + 3, image.indexOf(";data:"));
      final String fileType = image.substring(image.indexOf(";data:") + 6, image.indexOf(";base64,"));
      final String encodedImg = image.substring(image.indexOf(";base64,") + 8);
      sendIntent.setType(fileType);
      saveFile(Base64.decode(encodedImg, Base64.DEFAULT), dir, sanitizeFilename(fileName));
      localImage = "file://" + dir + "/" + sanitizeFilename(fileName);
    } else if (!image.startsWith("file://")) {
      throw new IllegalArgumentException("URL_NOT_SUPPORTED");
    } else {
      //get file MIME type
      String type = getMIMEType(image);
      //set intent data and Type
      sendIntent.setType(type);
    }
    return Uri.parse(localImage);
  }

  private String getMIMEType(String fileName) {
    String type = "*/*";
    int dotIndex = fileName.lastIndexOf(".");
    if (dotIndex == -1) {
      return type;
    }
    final String end = fileName.substring(dotIndex+1, fileName.length()).toLowerCase();
    String fromMap = MIME_Map.get(end);
    return fromMap == null ? type : fromMap;
  }

  private static final Map<String, String> MIME_Map = new HashMap<String, String>();
  static {
    MIME_Map.put("3gp",   "video/3gpp");
    MIME_Map.put("apk",   "application/vnd.android.package-archive");
    MIME_Map.put("asf",   "video/x-ms-asf");
    MIME_Map.put("avi",   "video/x-msvideo");
    MIME_Map.put("bin",   "application/octet-stream");
    MIME_Map.put("bmp",   "image/bmp");
    MIME_Map.put("c",     "text/plain");
    MIME_Map.put("class", "application/octet-stream");
    MIME_Map.put("conf",  "text/plain");
    MIME_Map.put("cpp",   "text/plain");
    MIME_Map.put("doc",   "application/msword");
    MIME_Map.put("docx",  "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    MIME_Map.put("xls",   "application/vnd.ms-excel");
    MIME_Map.put("xlsx",  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    MIME_Map.put("exe",   "application/octet-stream");
    MIME_Map.put("gif",   "image/gif");
    MIME_Map.put("gtar",  "application/x-gtar");
    MIME_Map.put("gz",    "application/x-gzip");
    MIME_Map.put("h",     "text/plain");
    MIME_Map.put("htm",   "text/html");
    MIME_Map.put("html",  "text/html");
    MIME_Map.put("jar",   "application/java-archive");
    MIME_Map.put("java",  "text/plain");
    MIME_Map.put("jpeg",  "image/jpeg");
    MIME_Map.put("jpg",   "image/*");
    MIME_Map.put("js",    "application/x-javascript");
    MIME_Map.put("log",   "text/plain");
    MIME_Map.put("m3u",   "audio/x-mpegurl");
    MIME_Map.put("m4a",   "audio/mp4a-latm");
    MIME_Map.put("m4b",   "audio/mp4a-latm");
    MIME_Map.put("m4p",   "audio/mp4a-latm");
    MIME_Map.put("m4u",   "video/vnd.mpegurl");
    MIME_Map.put("m4v",   "video/x-m4v");
    MIME_Map.put("mov",   "video/quicktime");
    MIME_Map.put("mp2",   "audio/x-mpeg");
    MIME_Map.put("mp3",   "audio/x-mpeg");
    MIME_Map.put("mp4",   "video/mp4");
    MIME_Map.put("mpc",   "application/vnd.mpohun.certificate");
    MIME_Map.put("mpe",   "video/mpeg");
    MIME_Map.put("mpeg",  "video/mpeg");
    MIME_Map.put("mpg",   "video/mpeg");
    MIME_Map.put("mpg4",  "video/mp4");
    MIME_Map.put("mpga",  "audio/mpeg");
    MIME_Map.put("msg",   "application/vnd.ms-outlook");
    MIME_Map.put("ogg",   "audio/ogg");
    MIME_Map.put("pdf",   "application/pdf");
    MIME_Map.put("png",   "image/png");
    MIME_Map.put("pps",   "application/vnd.ms-powerpoint");
    MIME_Map.put("ppt",   "application/vnd.ms-powerpoint");
    MIME_Map.put("pptx",  "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    MIME_Map.put("prop",  "text/plain");
    MIME_Map.put("rc",    "text/plain");
    MIME_Map.put("rmvb",  "audio/x-pn-realaudio");
    MIME_Map.put("rtf",   "application/rtf");
    MIME_Map.put("sh",    "text/plain");
    MIME_Map.put("tar",   "application/x-tar");
    MIME_Map.put("tgz",   "application/x-compressed");
    MIME_Map.put("txt",   "text/plain");
    MIME_Map.put("wav",   "audio/x-wav");
    MIME_Map.put("wma",   "audio/x-ms-wma");
    MIME_Map.put("wmv",   "audio/x-ms-wmv");
    MIME_Map.put("wps",   "application/vnd.ms-works");
    MIME_Map.put("xml",   "text/plain");
    MIME_Map.put("z",     "application/x-compress");
    MIME_Map.put("zip",   "application/x-zip-compressed");
    MIME_Map.put("",       "*/*");
  }

  private ActivityInfo getActivity(final CallbackContext callbackContext, final Intent shareIntent, final String appPackageName, final String appName) {
    final PackageManager pm = webView.getContext().getPackageManager();
    List<ResolveInfo> activityList = pm.queryIntentActivities(shareIntent, 0);
    for (final ResolveInfo app : activityList) {
      if ((app.activityInfo.packageName).contains(appPackageName)) {
        if (appName == null || (app.activityInfo.name).contains(appName)) {
          return app.activityInfo;
        }
      }
    }
    // no matching app found
    callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, getShareActivities(activityList)));
    return null;
  }

  private JSONArray getShareActivities(List<ResolveInfo> activityList) {
    List<String> packages = new ArrayList<String>();
    for (final ResolveInfo app : activityList) {
      packages.add(app.activityInfo.packageName);
    }
    return new JSONArray(packages);
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent intent) {
    super.onActivityResult(requestCode, resultCode, intent);
    if (_callbackContext != null) {
      switch (requestCode) {
        case ACTIVITY_CODE_SEND__BOOLRESULT:
          _callbackContext.sendPluginResult(new PluginResult(
              PluginResult.Status.OK,
              resultCode == Activity.RESULT_OK));
          break;
        case ACTIVITY_CODE_SEND__OBJECT:
          JSONObject json = new JSONObject();
          try {
            json.put("completed", resultCode == Activity.RESULT_OK);
            json.put("app", (ShareChooserPendingIntent.chosenComponent != null ? ShareChooserPendingIntent.chosenComponent : "")); // we need a completely different approach if we want to support this on Android. Idea: https://clickclickclack.wordpress.com/2012/01/03/intercepting-androids-action_send-intents/
            _callbackContext.sendPluginResult(new PluginResult(
                PluginResult.Status.OK,
                json));
          } catch (JSONException e) {
            _callbackContext.error(e.getMessage());
          }
          break;
        default:
          _callbackContext.success();
      }
    }
  }

  private void createOrCleanDir(final String downloadDir) throws IOException {
    final File dir = new File(downloadDir);
    if (!dir.exists()) {
      if (!dir.mkdirs()) {
        throw new IOException("CREATE_DIRS_FAILED");
      }
    } else {
      cleanupOldFiles(dir);
    }
  }

  private static String getFileName(String url) {
    if (url.endsWith("/")) {
      url = url.substring(0, url.length()-1);
    }
    final String pattern = ".*/([^?#]+)?";
    Pattern r = Pattern.compile(pattern);
    Matcher m = r.matcher(url);
    if (m.find()) {
      return m.group(1);
    } else {
      return "file";
    }
  }

  private byte[] getBytes(InputStream is) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int nRead;
    byte[] data = new byte[16384];
    while ((nRead = is.read(data, 0, data.length)) != -1) {
      buffer.write(data, 0, nRead);
    }
    buffer.flush();
    return buffer.toByteArray();
  }

  private void saveFile(byte[] bytes, String dirName, String fileName) throws IOException {
    final File dir = new File(dirName);
    final FileOutputStream fos = new FileOutputStream(new File(dir, fileName));
    fos.write(bytes);
    fos.flush();
    fos.close();
  }

  /**
   * As file.deleteOnExit does not work on Android, we need to delete files manually.
   * Deleting them in onActivityResult is not a good idea, because for example a base64 encoded file
   * will not be available for upload to Facebook (it's deleted before it's uploaded).
   * So the best approach is deleting old files when saving (sharing) a new one.
   */
  private void cleanupOldFiles(File dir) {
    for (File f : dir.listFiles()) {
      //noinspection ResultOfMethodCallIgnored
      f.delete();
    }
  }

  private static boolean notEmpty(String what) {
    return what != null &&
        !"".equals(what) &&
        !"null".equalsIgnoreCase(what);
  }

  private static String[] toStringArray(JSONArray jsonArray) throws JSONException {
    String[] result = new String[jsonArray.length()];
    for (int i = 0; i < jsonArray.length(); i++) {
      result[i] = jsonArray.getString(i);
    }
    return result;
  }

  public static String sanitizeFilename(String name) {
    return name.replaceAll("[:\\\\/*?|<> ]", "_");
  }
}
