package com.example.batterytest;

import androidx.appcompat.app.AppCompatActivity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import com.example.batterytest.databinding.ActivityMainBinding;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    TimerTask mFtpTimerTask;
    TimerTask mBarcodeTimerTask;
    FTPClient mClient;
    Handler mViewHandler;

    boolean mLogin = false;
    boolean mIsFtpStart = false;
    boolean mIsBatteryStart = false;

    private String mCurrentStatus="";
    private int mBarcodeCount = 0;
    private int mFtpUpLoadCount = 0;
    private int mFtpDownLoadCount = 0;
    private boolean mIsRegisterReceiver;
    private boolean mIsOpened = false;
    private int mBarcodeHandle = -1;

    private static final int SEQ_BARCODE_OPEN = 100;
    private static final int SEQ_BARCODE_CLOSE = 200;
    private static final int SEQ_BARCODE_SET_TRIGGER_ON = 400;
    private static final int SEQ_BARCODE_SET_TRIGGER_OFF = 500;

    private static LocalDateTime mStartTime;
    private static final String STATUS_OPEN = "STATUS_OPEN";
    private static final String STATUS_CLOSE = "STATUS_CLOSE";
    private static final String TAG = MainActivity.class.getName();

    private static final String DATE_FORMAT = "yyyy/MM/dd HH:mm:ss";

    WifiManager.WifiLock wifiLock = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        View view = binding.getRoot();
        setContentView(view);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mStartTime = LocalDateTime.now();
        }

        wifiLock();
        mViewHandler = new Handler();
        registerReceiver();

        initialize();
        buttonEvent();
    }

    private void wifiLock(){

        if(wifiLock == null){
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiLock = wifiManager.createWifiLock("wifilock");
            wifiLock.acquire();
        }
    }

    private void buttonEvent(){

        binding.btnFtpStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                actionStart();
                binding.btnFtpStart.setEnabled(false);
                binding.btnFtpStop.setEnabled(true);
            }
        });
        binding.btnFtpStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                actionStop();
            }
        });

    }

    private void actionStart(){

        binding.chronometer.setBase(SystemClock.elapsedRealtime());
        binding.chronometer.start();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mStartTime = LocalDateTime.now();
        }
        ftpAction();
        barcodeAction();
    }

    private void actionStop(){
        timeRecode();
        ftpActionStop();
        barcodeActionStop();

        setFtpDownloadCount(0);
        setFtpUploadCount(0);
        binding.btnFtpStart.setEnabled(true);
        binding.btnFtpStop.setEnabled(false);
    }


    //if start button action start. connect to ftp server and upload and download repeat
    private void ftpAction(){
        if(!checkInternetStatus()) { // check internet connect
            setFtpConnStatus(getString(R.string.internet_status));
            actionStop();
            return;
        }else{
            setFtpConnStatus("");

            if(!getIsFtpStart()){
                //       Log.d(TAG,"Login");
                FtpConnTask ftpConnTask = new FtpConnTask();
                ftpConnTask.execute();
                repeatFtp();
                binding.tvFtpConnectStatus.setText(R.string.ftp_connected);

            }
        }
    }

    //stop button action this function started
    //disconnect ftp and clear about it
    private void ftpActionStop(){
        //ftp
        if(getLogin()){
            FtpDisConnTask ftpDisConnTask = new FtpDisConnTask();
            ftpDisConnTask.execute();
        }
        ftpTimerEnd();
        binding.tvFtpConnectStatus.setText(getText(R.string.ftp_disconnect));
        binding.tvFtpStatus.setText("");
        binding.tvFtpStatus2.setText("");
        binding.tvBarcode.setText("");
    }

    //if start button action start. barcode reading is repeat
    private void barcodeAction(){
        if(!mIsOpened)
            setBarcode();
        if(!getIsBatteryStart())
            repeatBarcode();
        binding.tvRepeatStatus.setText(R.string.barcode_read_start);
    }

    //stop button action this function started
    //clear about barcode action
    private void barcodeActionStop(){
        barcodeTimerEnd();
        setTriggerOff();
        if(mIsOpened){
            setBarcode();
        }
        setRepeatErrorStatus(getString(R.string.barcode_read_stop));
    }

    //record test time
    // recode startTime, EndTime and Duration of Time in internal .txt file
    private void timeRecode(){
        binding.chronometer.stop();
        long startTime = binding.chronometer.getBase();
        Locale locale = getApplicationContext().getResources().getConfiguration().locale;
        long duringTime = SystemClock.elapsedRealtime() - startTime;

        String sStartTime = "";
        String sEndTime = "";
        String sDuringTime = "";
        String result = "";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            sStartTime = mStartTime.format(DateTimeFormatter.ofPattern(DATE_FORMAT, locale));
            sEndTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern(DATE_FORMAT, locale));
        }

        sDuringTime = String.valueOf(duringTime/1000);

        result = "Test Start Time : " + sStartTime + "\n"+
                "Test End Time : " + sEndTime + "\n"+
                "Duration of Time : " + sDuringTime +" seconds \n" ;
        WriteTimeInFile(result);
    }

//write start,end, duration time to txt file in storage
    private void WriteTimeInFile(String write){
        String fileName = getString(R.string.timeRecode_file_name);
        String pathDir = getDataDir().getAbsolutePath();

        String path = pathDir + File.separator + fileName;
        FileWriter fileWriter = null;

        try {
            fileWriter = new FileWriter(path);
            BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);
            bufferedWriter.write(write);
            bufferedWriter.close();
            fileWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        //release
        if(wifiLock != null){
            wifiLock.release();
            wifiLock = null;
        }

        super.onDestroy();
    }

    @Override
    protected void onPause() {
        destroyEvent();
        actionStop();
        mViewHandler = null;
        binding.chronometer.setBase(SystemClock.elapsedRealtime());
        super.onPause();
    }

    @Override
    protected void onResume() {
        registerReceiver();
        initialize();
        mViewHandler = new Handler();
        super.onResume();
    }

    //upload and download files once every 10 seconds
    private void repeatFtp() {
        setIsFtpStart(true);

        mFtpTimerTask = new TimerTask() {
            @Override
            public void run() {
                if(getLogin()){
                    ftpUpload();
                    ftpDownload();
                }
            }
        };
        Timer timer = new Timer();
        timer.schedule(mFtpTimerTask,0,10000);
    }

    //Read barcode once every 5 seconds
    private void repeatBarcode(){
        setIsBatteryStart(true);

        mBarcodeTimerTask = new TimerTask() {
            @Override
            public void run() {
                setTriggerOn();
            }
        };

        Timer timer = new Timer();
        timer.schedule(mBarcodeTimerTask, 0, 5000);
    }

    //stop timer
    void ftpTimerEnd(){
        if(mFtpTimerTask != null){
            mFtpTimerTask.cancel();
        }
        setIsFtpStart(false);
    }

    void barcodeTimerEnd(){
        if(mBarcodeTimerTask != null){
            mBarcodeTimerTask.cancel();
        }
        setIsBatteryStart(false);
    }

    //batteryStart is check that battery test is now start.
    //show is timertask start or stop
    private void setIsBatteryStart(boolean status){
        mIsBatteryStart = status;
    }

    private boolean getIsBatteryStart(){
        return mIsBatteryStart;
    }

    //IsFtpStart is check that ftp test is now start.
    //show is timertask start or stop
    private void setIsFtpStart(boolean status){
        mIsFtpStart = status;
    }

    private boolean getIsFtpStart(){
        return mIsFtpStart;
    }

    //mLogin is check that login with ftp server.
    private void setLogin(boolean status){
        mLogin = status;
    }
    private boolean getLogin(){
        return mLogin;
    }

    private void setFtpUploadCount(int n){
        mFtpUpLoadCount = n;
    }
    private int getFtpUploadCount(){
        return mFtpUpLoadCount;
    }

    private void setFtpDownloadCount(int n){
        mFtpDownLoadCount = n;
    }
    private int getFtpDownloadCount(){
        return mFtpDownLoadCount;
    }

    private void setBarcodeCount(int n){
        mBarcodeCount = n;
    }
    private int getBarcodeCount(){
        return mBarcodeCount;
    }


    //disconnect to ftp server
    void ftpFinish(){
        try {
            if(mClient.isConnected())
                mClient.disconnect();
        } catch (IOException e) {
            e.printStackTrace();
        }
        setLogin(false);
    }

    //connect to ftp server
    void ftpConnection() {
        mClient = new FTPClient();
        mClient.setRemoteVerificationEnabled(false);
        if(!getLogin()){
            try {
                mClient.connect(getString(R.string.server_hostname), 21);
                int resultCode = mClient.getReplyCode();
                Log.d(TAG, String.valueOf(resultCode));

                mClient.setControlKeepAliveTimeout(1);
                mClient.login(getString(R.string.server_username), getString(R.string.server_password));

                if(FTPReply.isPositiveCompletion(resultCode)){
                    setLogin(true);
                }else{
                    setFtpConnStatus(getString(R.string.ftp_connection_err));
                    actionStop();
                }

            } catch (ConnectException exception){
                exception.printStackTrace();
                setFtpConnStatus(getString(R.string.ftp_connection_err));
                actionStop();
            } catch (SocketException socketException) {
                socketException.printStackTrace();
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
        }
    }

    //upload file to ftp server
    void ftpUpload(){
        String fileName = getString(R.string.upload_file_name);
        InputStream fileInputStream = null;

        try {
            fileInputStream = getResources().openRawResource(R.raw.send);

            boolean isSuccess = mClient.storeFile(fileName, fileInputStream);

            if(isSuccess){
                setFtpUploadCount(getFtpUploadCount()+1);
                setFtpFirstStatus(getString(R.string.upload_complete)+" [Count] : "+getFtpUploadCount());
                Log.d(TAG,getString(R.string.upload_complete)+ " [Count] : "+getFtpUploadCount());
            }
            else{
                setFtpFirstStatus(getString(R.string.upload_fail));
                Log.d(TAG,getString(R.string.upload_fail));
            }

            fileInputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    //download file to ftp server
    void ftpDownload() {
        String fileName = getString(R.string.download_file_name);
        String pathDir = getDataDir().getAbsolutePath();

        String path = pathDir + File.separator + fileName;
        OutputStream outputStream = null;

        try {
            outputStream = new FileOutputStream(path);

            if (mClient.retrieveFile(fileName, outputStream)) {
                setFtpDownloadCount(getFtpDownloadCount()+1);
                Log.d(TAG,getString(R.string.download_complete)+" [Count] : "+ getFtpDownloadCount());
                setFtpSecondStatus(getString(R.string.download_complete)+" [Count] : "+getFtpDownloadCount());
            }else{
                Log.d(TAG,getString(R.string.download_fail));
                setFtpSecondStatus(getString(R.string.download_fail));
            }
            outputStream.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    //show ftp file upload, connection status
    private void setFtpFirstStatus(String s){
        Runnable updater = new Runnable() {
            @Override
            public void run() {
                binding.tvFtpStatus.setText(s);
            }
        };
        mViewHandler.post(updater);
    }

    //show ft file downloadStatus
    private void setFtpSecondStatus(String s){
        Runnable updater = new Runnable() {
            @Override
            public void run() {
                binding.tvFtpStatus2.setText(s);
            }
        };
        mViewHandler.post(updater);
    }

    //show ftp connection status
    private void setFtpConnStatus(String s){
        Runnable updater = new Runnable() {
            @Override
            public void run() {
                binding.tvFtpInternetStatus.setText(s);
            }
        };
        mViewHandler.post(updater);
    }

    //show ftp error
    private void setRepeatErrorStatus(String s){
        Runnable updater = new Runnable() {
            @Override
            public void run() {
                binding.tvRepeatStatus.setText(s);
            }
        };
        mViewHandler.post(updater);
    }

    private void initialize()
    {
        mCurrentStatus = STATUS_CLOSE;
        mIsRegisterReceiver = false;
    }

    //check internet connection
    private boolean checkInternetStatus(){
        Context context = getApplicationContext();
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(context.CONNECTIVITY_SERVICE);

        NetworkInfo networkInfo = manager.getActiveNetworkInfo();
        if(networkInfo != null){
            return networkInfo.isConnectedOrConnecting();
        }

        return false;
    }

    //read barcode and trigger on
    private void setTriggerOn() {
        Intent intent = new Intent();
        intent.setAction(Constants.ACTION_BARCODE_SET_TRIGGER);
        intent.putExtra(Constants.EXTRA_HANDLE, mBarcodeHandle);
        intent.putExtra(Constants.EXTRA_INT_DATA2, 1);
        intent.putExtra(Constants.EXTRA_INT_DATA3, SEQ_BARCODE_SET_TRIGGER_ON);
        sendBroadcast(intent);
        Log.d(TAG,"SET_TRIGGER : ON");
    }

    private void setTriggerOff(){
        Intent intent = new Intent();
        intent.setAction(Constants.ACTION_BARCODE_SET_TRIGGER);
        intent.putExtra(Constants.EXTRA_HANDLE, mBarcodeHandle);
        intent.putExtra(Constants.EXTRA_INT_DATA2, 0);
        intent.putExtra(Constants.EXTRA_INT_DATA3, SEQ_BARCODE_SET_TRIGGER_OFF);
        sendBroadcast(intent);
        Log.d(TAG,"SET_TRIGGER : OFF");
    }

    private void setBarcode(){
        Intent intent = new Intent();
        setBarcodeCount(0);
        String action = Constants.ACTION_BARCODE_CLOSE;
        int seq = SEQ_BARCODE_CLOSE;

        if(mCurrentStatus.equals(STATUS_CLOSE))
            action = Constants.ACTION_BARCODE_OPEN;
        intent.setAction(action);

        if(mIsOpened)
            intent.putExtra(Constants.EXTRA_HANDLE, mBarcodeHandle);
        if(mCurrentStatus.equals(STATUS_CLOSE))
            seq = SEQ_BARCODE_OPEN;
        intent.putExtra(Constants.EXTRA_INT_DATA3, seq);
        sendBroadcast(intent);

        if(mCurrentStatus.equals(STATUS_CLOSE)) {
            mIsOpened = true;
            Log.d(TAG,"barcode close");
        }
        else {
            mIsOpened = false;
            Log.d(TAG,"barcode open");
        }
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            int handle = intent.getIntExtra(Constants.EXTRA_HANDLE, 0);
            int seq = intent.getIntExtra(Constants.EXTRA_INT_DATA3, 0);

            if(action.equals(Constants.ACTION_BARCODE_CALLBACK_DECODING_DATA))
            {
                setBarcodeCount(getBarcodeCount()+1);
                byte[] data = intent.getByteArrayExtra(Constants.EXTRA_BARCODE_DECODING_DATA);
                int symbology = intent.getIntExtra(Constants.EXTRA_INT_DATA2, -1);
                String result = "";
                String dataResult = "";
                if(data!=null)
                {
                    dataResult = new String(data);
                    if(dataResult.contains("�"))
                    {
                        try {
                            dataResult = new String(data, "Shift-JIS");
                        } catch (UnsupportedEncodingException e) {
                            e.printStackTrace();
                        }
                    }
                }
                result += "[Data] : "+dataResult;
                result += " [Count] : "+getBarcodeCount();
                Log.d(TAG,result);
                binding.tvBarcode.setText(result);

            }
            else if(action.equals(Constants.ACTION_BARCODE_CALLBACK_REQUEST_SUCCESS))
            {
                Log.d(TAG,"Success : "+seq);

                if(seq == SEQ_BARCODE_OPEN)
                {
                    mBarcodeHandle = intent.getIntExtra(Constants.EXTRA_HANDLE, 0);
                    mCurrentStatus = STATUS_OPEN;
                }
                else if(seq == SEQ_BARCODE_CLOSE)
                {
                    mCurrentStatus = STATUS_CLOSE;
                }

            }
            else if(action.equals(Constants.ACTION_BARCODE_CALLBACK_REQUEST_FAILED))
            {
                int result = intent.getIntExtra(Constants.EXTRA_INT_DATA2, 0);
                if(result == Constants.ERROR_BARCODE_DECODING_TIMEOUT)
                {
                    Log.d(TAG,"Failed result : "+"Decode Timeout"+" / seq : "+seq);
                    binding.tvBarcode.setText("Failed result : "+"Decode Timeout");
                }
                else if(result == Constants.ERROR_NOT_SUPPORTED)
                {
                    Log.d(TAG,"Failed result : "+"Not Supported"+" / seq : "+seq);
                    binding.tvBarcode.setText("Failed result : "+"Not Supported");
                }
                else if(result == Constants.ERROR_BARCODE_ERROR_USE_TIMEOUT)
                {
                    mCurrentStatus = STATUS_CLOSE;
                    Log.d(TAG,"Failed result : "+"Use Timeout"+" / seq : "+seq);
                    binding.tvBarcode.setText("Failed result : \"+\"Use Timeout");
                }
                else if(result == Constants.ERROR_BARCODE_ERROR_ALREADY_OPENED)
                {
                    mCurrentStatus = STATUS_OPEN;
                    Log.d(TAG,"Failed result : "+"Already opened"+" / seq : "+seq);
                    binding.tvBarcode.setText("Failed result : "+"Already opened");
                }
                else if(result == Constants.ERROR_BATTERY_LOW)
                {
                    mCurrentStatus = STATUS_CLOSE;
                    Log.d(TAG,"Failed result : "+"Battery low"+" / seq : "+seq);
                    binding.tvBarcode.setText("Failed result : "+"Battery low");
                    actionStop();
                }
                else if(result == Constants.ERROR_NO_RESPONSE)
                {
                    int notiCode = intent.getIntExtra(Constants.EXTRA_INT_DATA3, 0);
                    Log.d(TAG,"Failed result : "+ notiCode+"/ ### ERROR_NO_RESPONSE ###");
                    binding.tvBarcode.setText("Failed result : "+ notiCode+"/ ### ERROR_NO_RESPONSE ###");
                    mCurrentStatus = STATUS_CLOSE;
                    Log.d(TAG,"Failed result : "+result+" / seq : "+seq);
                }
                else
                {
                    Log.d(TAG,"Failed result : "+result+" / seq : "+seq);
                    binding.tvBarcode.setText("Failed result : "+result);
                }
            }else if(action == null) {
                Log.d(TAG, "Null Broadcast");
                binding.tvBarcode.setText("Null Broadcast");
            }
        }
    };

    private void registerReceiver() {
        if(mIsRegisterReceiver)
            return;

        IntentFilter filter = new IntentFilter();
        filter.addAction(Constants.ACTION_BARCODE_CALLBACK_DECODING_DATA);
        filter.addAction(Constants.ACTION_BARCODE_CALLBACK_REQUEST_SUCCESS);
        filter.addAction(Constants.ACTION_BARCODE_CALLBACK_REQUEST_FAILED);

        registerReceiver(mReceiver, filter);
        mIsRegisterReceiver = true;
    }

    private void unregisterReceiver()
    {
        if(!mIsRegisterReceiver) return;
        unregisterReceiver(mReceiver);
        mIsRegisterReceiver = false;
    }

    //must have to close when app destroyed
    private void destroyEvent()
    {
        if(!mCurrentStatus.equals(STATUS_CLOSE))
        {
            Intent intent = new Intent();
            intent.setAction(Constants.ACTION_BARCODE_CLOSE);
            intent.putExtra(Constants.EXTRA_HANDLE, mBarcodeHandle);
            intent.putExtra(Constants.EXTRA_INT_DATA3, SEQ_BARCODE_CLOSE);
            sendBroadcast(intent);
        }
        unregisterReceiver();
    }

    private class FtpConnTask extends AsyncTask<Void,Void,Void> {
        @Override
        protected Void doInBackground(Void... voids) {
            ftpConnection();
            return null;
        }

        @Override
        protected void onPostExecute(Void unused) {
            super.onPostExecute(unused);
        }

    }

    private class FtpDisConnTask extends AsyncTask<Void,Void,Void>{

        @Override
        protected Void doInBackground(Void... voids) {
            ftpFinish();
            return null;
        }

        @Override
        protected void onPostExecute(Void unused) {
            super.onPostExecute(unused);
        }
    }

}