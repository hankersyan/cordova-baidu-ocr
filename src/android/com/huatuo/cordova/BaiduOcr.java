package com.huatuo.cordova;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Looper;
import android.provider.MediaStore;
import android.support.v4.app.ActivityCompat;
import android.text.TextUtils;
import android.util.Log;

import com.baidu.ocr.sdk.OCR;
import com.baidu.ocr.sdk.OnResultListener;
import com.baidu.ocr.sdk.exception.OCRError;
import com.baidu.ocr.sdk.model.AccessToken;
import com.baidu.ocr.sdk.model.IDCardParams;
import com.baidu.ocr.sdk.model.IDCardResult;
import com.baidu.ocr.ui.camera.CameraActivity;
import com.baidu.ocr.ui.camera.CameraNativeHelper;
import com.baidu.ocr.ui.camera.CameraView;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.logging.Handler;

public class BaiduOcr extends CordovaPlugin {

    private static String TAG = BaiduOcr.class.getSimpleName();

    private static final int REQUEST_CODE_PICK_IMAGE_FRONT = 201;
    private static final int REQUEST_CODE_PICK_IMAGE_BACK = 202;
    private static final int REQUEST_CODE_CAMERA = 102;

    private static final int REQUEST_CODE_BANKCARD = 111;
    private static final int REQUEST_CODE_VEHICLE_LICENSE = 120;
    private static final int REQUEST_CODE_DRIVING_LICENSE = 121;
    private static final int REQUEST_CODE_LICENSE_PLATE = 122;
    private static final int REQUEST_CODE_BUSINESS_LICENSE = 123;
    private static final int REQUEST_CODE_RECEIPT = 124;
    private static final int REQUEST_CODE_PASSPORT = 125;
    private static final int REQUEST_CODE_NUMBERS = 126;
    private static final int REQUEST_CODE_QRCODE = 127;
    private static final int REQUEST_CODE_BUSINESSCARD = 128;
    private static final int REQUEST_CODE_HANDWRITING = 129;
    private static final int REQUEST_CODE_LOTTERY = 130;
    private static final int REQUEST_CODE_VATINVOICE = 131;
    private static final int REQUEST_CODE_CUSTOM = 132;

    private CallbackContext mCallback;
    private boolean hasGotToken = false;

    public BaiduOcr() {

    }

    /**
     * Sets the context of the Command. This can then be used to do things like
     * get file paths associated with the Activity.
     *
     * @param cordova The context of the main Activity.
     * @param webView The CordovaWebView Cordova is running in.
     */
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);

        //init access token
        initAccessToken();
    }

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action          The action to execute.
     * @param args            JSONArry of arguments for the plugin.
     * @param callbackContext The callback id used when calling back into JavaScript.
     * @return True if the action was valid, false if not.
     */
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {

        cordova.getThreadPool().execute(() -> {
            try {
                Method method = BaiduOcr.class.getDeclaredMethod(action, JSONArray.class, CallbackContext.class);
                method.invoke(BaiduOcr.this, args, callbackContext);
            } catch (Exception e) {
                Log.e(TAG, e.toString());
            }
        });
        return true;
    }

    /**
     * 初始化
     *
     * @param data
     * @param callbackContext
     * @throws JSONException
     */
    void init(JSONArray data, CallbackContext callbackContext) throws JSONException {
        //  初始化本地质量控制模型,释放代码在onDestory中
        //  调用身份证扫描必须加上 intent.putExtra(CameraActivity.KEY_NATIVE_MANUAL, true); 关闭自动初始化和释放本地模型
        CameraNativeHelper.init(cordova.getContext(), OCR.getInstance(cordova.getContext()).getLicense(),
                (errorCode, e) -> {
                    String msg;
                    switch (errorCode) {
                        case CameraView.NATIVE_SOLOAD_FAIL:
                            msg = "加载so失败，请确保apk中存在ui部分的so";
                            break;
                        case CameraView.NATIVE_AUTH_FAIL:
                            msg = "授权本地质量控制token获取失败";
                            break;
                        case CameraView.NATIVE_INIT_FAIL:
                            msg = "本地质量控制";
                            break;
                        default:
                            msg = String.valueOf(errorCode);
                    }

                    try {
                        JSONObject r = new JSONObject();
                        r.put("code", errorCode);
                        r.put("message", msg);
                        callbackContext.error(r);
                    } catch (JSONException e1) {
                        e1.printStackTrace();
                    }

                });
        Log.e(TAG, "CameraNativeHelper.init ok");

        new android.os.Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                //Do something here
                callbackContext.success();
            }
        }, 1000);
    }

    /**
     * 扫描
     *
     * @param data
     * @param callbackContext
     * @throws JSONException
     */
    void scanId(JSONArray data, CallbackContext callbackContext) throws JSONException {

        JSONObject errObj = new JSONObject();
        JSONObject params = null;

        Boolean nativeEnable = true;
        Boolean nativeEnableManual = true;
        String contentType = "";

        //如果百度认证未通过，则直接返回错误
        if (!hasGotToken) {
            errObj.put("code", -1);
            errObj.put("message", "please init ocr");
            callbackContext.error(errObj);
            return;
        }

        //判断入参是否为空
        if (data != null && data.length() > 0) {
            params = data.getJSONObject(0);
        } else {
            errObj.put("code", -1);
            errObj.put("message", "params is error");
            callbackContext.error(errObj);
            return;
        }
        //如果参数为空，或者参数中未找到contentType属性，则返回错误
        if (params == null || !params.has(CameraActivity.KEY_CONTENT_TYPE)) {
            errObj.put("code", -1);
            errObj.put("message", "contentType is null");
            callbackContext.error(errObj);
            return;
        }
        //contentType取值： IDCardFront(正面),IDCardBack(反面)
        if (params.has(CameraActivity.KEY_CONTENT_TYPE)) {
            contentType = params.getString(CameraActivity.KEY_CONTENT_TYPE);
        }
        //参数判断是否合法
        if (!contentType.equals(CameraActivity.CONTENT_TYPE_ID_CARD_FRONT) && !contentType.equals(CameraActivity.CONTENT_TYPE_ID_CARD_BACK)) {
            errObj.put("code", -1);
            errObj.put("message", "contentType value error");
            callbackContext.error(errObj);
            return;
        }

        if (params.has(CameraActivity.KEY_NATIVE_ENABLE)) {
            nativeEnable = params.getBoolean(CameraActivity.KEY_NATIVE_ENABLE);
        }
        if (params.has(CameraActivity.KEY_NATIVE_MANUAL)) {
            nativeEnableManual = params.getBoolean(CameraActivity.KEY_NATIVE_MANUAL);
        }

        Intent intent = new Intent(cordova.getActivity(), CameraActivity.class);
        intent.putExtra(CameraActivity.KEY_OUTPUT_FILE_PATH,
                FileUtil.getSaveFile(cordova.getActivity().getApplication()).getAbsolutePath());
        intent.putExtra(CameraActivity.KEY_NATIVE_ENABLE,
                nativeEnable);
        // KEY_NATIVE_MANUAL设置了之后CameraActivity中不再自动初始化和释放模型
        // 请手动使用CameraNativeHelper初始化和释放模型
        // 推荐这样做，可以避免一些activity切换导致的不必要的异常
        intent.putExtra(CameraActivity.KEY_NATIVE_MANUAL,
                nativeEnableManual);
        intent.putExtra(CameraActivity.KEY_CONTENT_TYPE, contentType);

        //设置回调
        mCallback = callbackContext;

        //把当前plugin设置为startActivityForResult的返回 ****重要****
        cordova.setActivityResultCallback(this);
        //启动扫描页面
        cordova.getActivity().startActivityForResult(intent, REQUEST_CODE_CAMERA);
    }

    void scanBase(int requestCode, CallbackContext callbackContext) throws JSONException {

        JSONObject errObj = new JSONObject();

        //如果百度认证未通过，则直接返回错误
        if (!hasGotToken) {
            errObj.put("code", -1);
            errObj.put("message", "please init ocr");
            callbackContext.error(errObj);
            return;
        }

        final String filePath = FileUtil.getSaveFile(cordova.getActivity().getApplication()).getAbsolutePath();

        Intent intent = new Intent(cordova.getActivity(), CameraActivity.class);
        intent.putExtra(CameraActivity.KEY_OUTPUT_FILE_PATH, filePath);
        intent.putExtra(CameraActivity.KEY_CONTENT_TYPE, CameraActivity.CONTENT_TYPE_BANK_CARD);

        //设置回调
        mCallback = callbackContext;
        //把当前plugin设置为startActivityForResult的返回 ****重要****
        cordova.setActivityResultCallback(this);
        //启动扫描页面
        cordova.getActivity().startActivityForResult(intent, requestCode);
    }

    void scanBankCard(JSONArray data, CallbackContext callbackContext) throws JSONException {
        scanBase(REQUEST_CODE_BANKCARD, callbackContext);
    }

    void scanVehicleLicense(JSONArray data, CallbackContext callbackContext) throws JSONException {
        scanBase(REQUEST_CODE_VEHICLE_LICENSE, callbackContext);
    }

    void scanDrivingLicense(JSONArray data, CallbackContext callbackContext) throws JSONException {
        scanBase(REQUEST_CODE_DRIVING_LICENSE, callbackContext);
    }

    void scanLicensePlate(JSONArray data, CallbackContext callbackContext) throws JSONException {
        scanBase(REQUEST_CODE_LICENSE_PLATE, callbackContext);
    }

    void scanBusinessLicense(JSONArray data, CallbackContext callbackContext) throws JSONException {
        scanBase(REQUEST_CODE_BUSINESS_LICENSE, callbackContext);
    }

    void scanReceipt(JSONArray data, CallbackContext callbackContext) throws JSONException {
        scanBase(REQUEST_CODE_RECEIPT, callbackContext);
    }

    void scanPassport(JSONArray data, CallbackContext callbackContext) throws JSONException {
        scanBase(REQUEST_CODE_PASSPORT, callbackContext);
    }

    void scanNumbers(JSONArray data, CallbackContext callbackContext) throws JSONException {
        scanBase(REQUEST_CODE_NUMBERS, callbackContext);
    }

    void scanQrCode(JSONArray data, CallbackContext callbackContext) throws JSONException {
        scanBase(REQUEST_CODE_QRCODE, callbackContext);
    }

    void scanBusinessCard(JSONArray data, CallbackContext callbackContext) throws JSONException {
        scanBase(REQUEST_CODE_BUSINESSCARD, callbackContext);
    }

    void scanHandWriting(JSONArray data, CallbackContext callbackContext) throws JSONException {
        scanBase(REQUEST_CODE_HANDWRITING, callbackContext);
    }

    void scanLottery(JSONArray data, CallbackContext callbackContext) throws JSONException {
        scanBase(REQUEST_CODE_LOTTERY, callbackContext);
    }

    void scanVatInvoice(JSONArray data, CallbackContext callbackContext) throws JSONException {
        scanBase(REQUEST_CODE_VATINVOICE, callbackContext);
    }

    void scanCustom(JSONArray data, CallbackContext callbackContext) throws JSONException {
        scanBase(REQUEST_CODE_CUSTOM, callbackContext);
    }

    /**
     * 销毁
     *
     * @param data
     * @param callbackContext
     * @throws JSONException
     */
    void destroy(JSONArray data, CallbackContext callbackContext) throws JSONException {
        // 释放本地质量控制模型
        CameraNativeHelper.release();
        callbackContext.success();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_PICK_IMAGE_FRONT && resultCode == Activity.RESULT_OK) {
            Uri uri = data.getData();
            String filePath = getRealPathFromURI(uri);
            recIDCard(IDCardParams.ID_CARD_SIDE_FRONT, filePath);
        }

        if (requestCode == REQUEST_CODE_PICK_IMAGE_BACK && resultCode == Activity.RESULT_OK) {
            Uri uri = data.getData();
            String filePath = getRealPathFromURI(uri);
            recIDCard(IDCardParams.ID_CARD_SIDE_BACK, filePath);
        }

        final String filePath = FileUtil.getSaveFile(cordova.getActivity().getApplicationContext()).getAbsolutePath();

        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQUEST_CODE_CAMERA) {
                if (data != null) {
                    String contentType = data.getStringExtra(CameraActivity.KEY_CONTENT_TYPE);
                    if (!TextUtils.isEmpty(contentType)) {
                        if (CameraActivity.CONTENT_TYPE_ID_CARD_FRONT.equals(contentType)) {
                            recIDCard(IDCardParams.ID_CARD_SIDE_FRONT, filePath);
                        } else if (CameraActivity.CONTENT_TYPE_ID_CARD_BACK.equals(contentType)) {
                            recIDCard(IDCardParams.ID_CARD_SIDE_BACK, filePath);
                        }
                    }
                }
            } else if (requestCode == REQUEST_CODE_BANKCARD) {
                RecognizeService.recBankCard(cordova.getActivity(), filePath,
                        new RecognizeService.ServiceListener() {
                            @Override
                            public void onResult(String result) {
                                mCallback.success(result);
                            }
                        });
            } else if (requestCode == REQUEST_CODE_VEHICLE_LICENSE) {
                RecognizeService.recVehicleLicense(cordova.getActivity(), filePath,
                        new RecognizeService.ServiceListener() {
                            @Override
                            public void onResult(String result) {
                                mCallback.success(result);
                            }
                        });
            } else if (requestCode == REQUEST_CODE_DRIVING_LICENSE) {
                RecognizeService.recDrivingLicense(cordova.getActivity(), filePath,
                        new RecognizeService.ServiceListener() {
                            @Override
                            public void onResult(String result) {
                                mCallback.success(result);
                            }
                        });
            } else if (requestCode == REQUEST_CODE_LICENSE_PLATE) {
                RecognizeService.recLicensePlate(cordova.getActivity(), filePath,
                        new RecognizeService.ServiceListener() {
                            @Override
                            public void onResult(String result) {
                                mCallback.success(result);
                            }
                        });
            } else if (requestCode == REQUEST_CODE_BUSINESS_LICENSE) {
                RecognizeService.recBusinessLicense(cordova.getActivity(), filePath,
                        new RecognizeService.ServiceListener() {
                            @Override
                            public void onResult(String result) {
                                mCallback.success(result);
                            }
                        });
            } else if (requestCode == REQUEST_CODE_RECEIPT) {
                RecognizeService.recReceipt(cordova.getActivity(), filePath,
                        new RecognizeService.ServiceListener() {
                            @Override
                            public void onResult(String result) {
                                mCallback.success(result);
                            }
                        });
            } else if (requestCode == REQUEST_CODE_PASSPORT) {
                RecognizeService.recPassport(cordova.getActivity(), filePath,
                        new RecognizeService.ServiceListener() {
                            @Override
                            public void onResult(String result) {
                                mCallback.success(result);
                            }
                        });
            } else if (requestCode == REQUEST_CODE_NUMBERS) {
                RecognizeService.recNumbers(cordova.getActivity(), filePath,
                        new RecognizeService.ServiceListener() {
                            @Override
                            public void onResult(String result) {
                                mCallback.success(result);
                            }
                        });
            } else if (requestCode == REQUEST_CODE_QRCODE) {
                RecognizeService.recQrcode(cordova.getActivity(), filePath,
                        new RecognizeService.ServiceListener() {
                            @Override
                            public void onResult(String result) {
                                mCallback.success(result);
                            }
                        });
            } else if (requestCode == REQUEST_CODE_BUSINESSCARD) {
                RecognizeService.recBusinessCard(cordova.getActivity(), filePath,
                        new RecognizeService.ServiceListener() {
                            @Override
                            public void onResult(String result) {
                                mCallback.success(result);
                            }
                        });
            } else if (requestCode == REQUEST_CODE_HANDWRITING) {
                RecognizeService.recHandwriting(cordova.getActivity(), filePath,
                        new RecognizeService.ServiceListener() {
                            @Override
                            public void onResult(String result) {
                                mCallback.success(result);
                            }
                        });
            } else if (requestCode == REQUEST_CODE_LOTTERY) {
                RecognizeService.recLottery(cordova.getActivity(), filePath,
                        new RecognizeService.ServiceListener() {
                            @Override
                            public void onResult(String result) {
                                mCallback.success(result);
                            }
                        });
            } else if (requestCode == REQUEST_CODE_VATINVOICE) {
                RecognizeService.recVatInvoice(cordova.getActivity(), filePath,
                        new RecognizeService.ServiceListener() {
                            @Override
                            public void onResult(String result) {
                                mCallback.success(result);
                            }
                        });
            } else if (requestCode == REQUEST_CODE_CUSTOM) {
                RecognizeService.recCustom(cordova.getActivity(), filePath,
                        new RecognizeService.ServiceListener() {
                            @Override
                            public void onResult(String result) {
                                mCallback.success(result);
                            }
                        });
            }
        }

    }

    /**
     * 初始化
     */
    private void initAccessToken() {

        OCR.getInstance(cordova.getContext()).initAccessToken(new OnResultListener<AccessToken>() {
            @Override
            public void onResult(AccessToken accessToken) {
                String token = accessToken.getAccessToken();
                hasGotToken = true;
                toastMessage("licence方式获取token成功");
            }

            @Override
            public void onError(OCRError error) {
                error.printStackTrace();
                toastMessage("licence方式获取token失败");
            }
        }, cordova.getContext());
    }

    /**
     * 弹出吐司消息
     *
     * @param message 文本消息
     */
    private void toastMessage(String message) {
        cordova.getActivity().runOnUiThread(() -> {
            //Toast.makeText(cordova.getContext(), message, Toast.LENGTH_LONG).show();
        });
    }

    /**
     * 判断权限
     *
     * @return
     */
    private boolean checkGalleryPermission() {
        int ret = ActivityCompat.checkSelfPermission(cordova.getContext(), Manifest.permission
                .READ_EXTERNAL_STORAGE);
        if (ret != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(cordova.getActivity(),
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    1000);
            return false;
        }
        return true;
    }

    private String getRealPathFromURI(Uri contentURI) {
        String result;
        Cursor cursor = cordova.getActivity().getContentResolver().query(contentURI, null, null, null, null);
        if (cursor == null) { // Source is Dropbox or other similar local file path
            result = contentURI.getPath();
        } else {
            cursor.moveToFirst();
            int idx = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATA);
            result = cursor.getString(idx);
            cursor.close();
        }
        return result;
    }

    private void recIDCard(String idCardSide, String filePath) {
        IDCardParams param = new IDCardParams();
        param.setImageFile(new File(filePath));
        // 设置身份证正反面
        param.setIdCardSide(idCardSide);
        // 设置方向检测
        param.setDetectDirection(true);
        // 设置图像参数压缩质量0-100, 越大图像质量越好但是请求时间越长。 不设置则默认值为20
        param.setImageQuality(20);

        OCR.getInstance(cordova.getContext()).recognizeIDCard(param, new OnResultListener<IDCardResult>() {
            @Override
            public void onResult(IDCardResult result) {
                if (result != null && mCallback != null) {
                    Log.i(TAG, result.getJsonRes());
                    mCallback.success(result.getJsonRes());
                }
            }

            @Override
            public void onError(OCRError error) {
                if (error != null && mCallback != null) {
                    Log.i(TAG, error.toString());
                    mCallback.error(error.getMessage());
                }
            }
        });
    }

}
