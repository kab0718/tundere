package net.masaya3.sdldisplaysample;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.smartdevicelink.managers.CompletionListener;
import com.smartdevicelink.managers.SdlManager;
import com.smartdevicelink.managers.SdlManagerListener;
import com.smartdevicelink.managers.file.filetypes.SdlArtwork;
import com.smartdevicelink.managers.permission.PermissionElement;
import com.smartdevicelink.managers.permission.PermissionStatus;
import com.smartdevicelink.protocol.enums.FunctionID;
import com.smartdevicelink.proxy.RPCNotification;
import com.smartdevicelink.proxy.RPCResponse;
import com.smartdevicelink.proxy.rpc.DisplayCapabilities;
import com.smartdevicelink.proxy.rpc.GetVehicleData;
import com.smartdevicelink.proxy.rpc.GetVehicleDataResponse;
import com.smartdevicelink.proxy.rpc.OnHMIStatus;
import com.smartdevicelink.proxy.rpc.OnVehicleData;
import com.smartdevicelink.proxy.rpc.SubscribeVehicleData;
import com.smartdevicelink.proxy.rpc.UnsubscribeVehicleData;
import com.smartdevicelink.proxy.rpc.enums.AppHMIType;
import com.smartdevicelink.proxy.rpc.enums.FileType;
import com.smartdevicelink.proxy.rpc.enums.HMILevel;
import com.smartdevicelink.proxy.rpc.enums.SystemCapabilityType;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCNotificationListener;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCResponseListener;
import com.smartdevicelink.transport.BaseTransportConfig;
import com.smartdevicelink.transport.MultiplexTransportConfig;
import com.smartdevicelink.transport.TCPTransportConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Vector;

public class SdlService extends Service {

	private static final String TAG 					= "SDL Service";

	private static final String APP_NAME 				= "SDL Display";
	private static final String APP_ID 					= "8678309";

	private static final String ICON_FILENAME 			= "hello_sdl_icon.png";

	private static final int FOREGROUND_SERVICE_ID = 111;

	// TCP/IP transport config
	// The default port is 12345
	// The IP is of the machine that is running SDL Core
	private static final int TCP_PORT = 17338;
	private static final String DEV_MACHINE_IP_ADDRESS = "m.sdl.tools";

	// variable to create and call functions of the SyncProxy
	private SdlManager sdlManager = null;

	private static Integer first_odo;
	private int count = 0;

	private MediaPlayer mp1 = null;
	private MediaPlayer mp2 = null;
	private MediaPlayer mp3 = null;
	private MediaPlayer mp4 = null;
	private MediaPlayer mp5 = null;


	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		Log.d(TAG, "onCreate");
		super.onCreate();

		mp1 = MediaPlayer.create(this,R.raw.seika1);
		mp2 = MediaPlayer.create(this,R.raw.seika2);
		mp3 = MediaPlayer.create(this,R.raw.seika3);
		mp4 = MediaPlayer.create(this,R.raw.seika4);
		mp5 = MediaPlayer.create(this,R.raw.seika5);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			enterForeground();
		}
	}

	// Helper method to let the service enter foreground mode
	@SuppressLint("NewApi")
	public void enterForeground() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel channel = new NotificationChannel(APP_ID, "SdlService", NotificationManager.IMPORTANCE_DEFAULT);
			NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
			if (notificationManager != null) {
				notificationManager.createNotificationChannel(channel);
				Notification serviceNotification = new Notification.Builder(this, channel.getId())
						.setContentTitle("Connected through SDL")
						.setSmallIcon(R.drawable.ic_sdl)
						.build();
				startForeground(FOREGROUND_SERVICE_ID, serviceNotification);
			}
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		startProxy();
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			stopForeground(true);
		}

		if (sdlManager != null) {
			sdlManager.dispose();
		}

		super.onDestroy();
	}

	private void startProxy() {

		if (sdlManager == null) {
			Log.i(TAG, "Starting SDL Proxy");

			BaseTransportConfig transport = null;
			if (BuildConfig.TRANSPORT.equals("MULTI")) {
				int securityLevel;
				if (BuildConfig.SECURITY.equals("HIGH")) {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_HIGH;
				} else if (BuildConfig.SECURITY.equals("MED")) {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_MED;
				} else if (BuildConfig.SECURITY.equals("LOW")) {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_LOW;
				} else {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF;
				}
				transport = new MultiplexTransportConfig(this, APP_ID, securityLevel);
			} else if (BuildConfig.TRANSPORT.equals("TCP")) {
				transport = new TCPTransportConfig(TCP_PORT, DEV_MACHINE_IP_ADDRESS, true);
			} else if (BuildConfig.TRANSPORT.equals("MULTI_HB")) {
				MultiplexTransportConfig mtc = new MultiplexTransportConfig(this, APP_ID, MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF);
				mtc.setRequiresHighBandwidth(true);
				transport = mtc;
			}

			// The app type to be used
			final Vector<AppHMIType> appType = new Vector<>();
			appType.add(AppHMIType.MEDIA);


			// The manager listener helps you know when certain events that pertain to the SDL Manager happen
			// Here we will listen for ON_HMI_STATUS and ON_COMMAND notifications
			SdlManagerListener listener = new SdlManagerListener() {
				//private PRNDL beforePrndl = null;
				@Override
				public void onStart() {
					// HMI Status Listener
					sdlManager.addOnRPCNotificationListener(FunctionID.ON_HMI_STATUS, new OnRPCNotificationListener() {
						@Override
						public void onNotified(RPCNotification notification) {

							OnHMIStatus status = (OnHMIStatus) notification;
							if (status.getHmiLevel() == HMILevel.HMI_FULL && ((OnHMIStatus) notification).getFirstRun()) {

								checkTemplateType();

								checkPermission();

								setDisplayDefault();
							}
						}
					});

					//これをすると定期的にデータが取得可能
					sdlManager.addOnRPCNotificationListener(FunctionID.ON_VEHICLE_DATA, new OnRPCNotificationListener() {
						@Override
						public void onNotified(RPCNotification notification) {
							OnVehicleData onVehicleDataNotification = (OnVehicleData) notification;

							sdlManager.getScreenManager().beginTransaction();

							Float oil = onVehicleDataNotification.getEngineOilLife();

							SdlArtwork artwork = null;

							//走行距離に応じて画像切り替え・音声の再生
							Integer now_odo = onVehicleDataNotification.getOdometer();
							Integer this_odo = now_odo - first_odo;
							if(this_odo != null) {
								if (this_odo == 30) {
									if (sdlManager.getScreenManager().getPrimaryGraphic().getResourceId() != R.drawable.girl_2) {
										artwork = new SdlArtwork("girl_2.jpg", FileType.GRAPHIC_JPEG, R.drawable.girl_2, true);
									}
									mp2.start();
								} else if(this_odo == 60){
									if (sdlManager.getScreenManager().getPrimaryGraphic().getResourceId() != R.drawable.girl_3) {
										artwork = new SdlArtwork("girl_3.jpg", FileType.GRAPHIC_JPEG, R.drawable.girl_3, true);
									}
									mp3.start();
								} else if(this_odo == 90){
									if (sdlManager.getScreenManager().getPrimaryGraphic().getResourceId() != R.drawable.girl_3) {
										artwork = new SdlArtwork("girl_3.jpg", FileType.GRAPHIC_JPEG, R.drawable.girl_3, true);
									}
									mp4.start();
								} else if(this_odo == 120) {
									if (sdlManager.getScreenManager().getPrimaryGraphic().getResourceId() != R.drawable.girl_4) {
										artwork = new SdlArtwork("girl_4.jpg", FileType.GRAPHIC_JPEG, R.drawable.girl_4, true);
									}
									mp5.start();
								}
								if (artwork != null) {
									sdlManager.getScreenManager().setPrimaryGraphic(artwork);
								}
							}

							//テキストを登録する場合
							sdlManager.getScreenManager().setTextField1("走行距離: " + this_odo + "km");
							sdlManager.getScreenManager().setTextField2("通算走行距離: " + now_odo + "km");




							/*
							PRNDL prndl = onVehicleDataNotification.getPrndl();
							if (prndl != null) {
								sdlManager.getScreenManager().setTextField2("ParkBrake: " + prndl.toString());

								//パーキングブレーキの状態が変わった時だけSpeedを受信させる
								if(beforePrndl != prndl){
									beforePrndl = prndl;
									setOnTimeSpeedResponse();
								}
							}
							*/

							sdlManager.getScreenManager().commit(new CompletionListener() {
								@Override
								public void onComplete(boolean success) {
									if (success) {
										Log.i(TAG, "change successful");
									}
								}
							});
						}
					});
				}

				@Override
				public void onDestroy() {
					UnsubscribeVehicleData unsubscribeRequest = new UnsubscribeVehicleData();
					unsubscribeRequest.setOdometer(true);
					unsubscribeRequest.setOnRPCResponseListener(new OnRPCResponseListener() {
						@Override
						public void onResponse(int correlationId, RPCResponse response) {
							if(response.getSuccess()){
								Log.i("SdlService", "Successfully unsubscribed to vehicle data.");
							}else{
								Log.i("SdlService", "Request to unsubscribe to vehicle data was rejected.");
							}
						}
					});
					sdlManager.sendRPC(unsubscribeRequest);

					SdlService.this.stopSelf();
				}

				@Override
				public void onError(String info, Exception e) {
				}
			};

			// Create App Icon, this is set in the SdlManager builder
			SdlArtwork appIcon = new SdlArtwork(ICON_FILENAME, FileType.GRAPHIC_PNG, R.mipmap.ic_launcher, true);

			// The manager builder sets options for your session
			SdlManager.Builder builder = new SdlManager.Builder(this, APP_ID, APP_NAME, listener);
			builder.setAppTypes(appType);
			builder.setTransportType(transport);
			builder.setAppIcon(appIcon);
			sdlManager = builder.build();
			sdlManager.start();


		}
	}

	/**
	 * 一度だけの情報受信
	 */
	private void setOnTimeSpeedResponse(){

		GetVehicleData vdRequest = new GetVehicleData();
		//vdRequest.setSpeed(true);
		vdRequest.setOdometer(true);
		vdRequest.setOnRPCResponseListener(new OnRPCResponseListener() {
			@Override
			public void onResponse(int correlationId, RPCResponse response) {
				if(response.getSuccess()){
					if(count == 0) {
						first_odo = ((GetVehicleDataResponse) response).getOdometer();
						changeSpeedTextField(first_odo);
						count++;
					}
				}else{
					Log.i("SdlService", "GetVehicleData was rejected.");
				}
			}
		});
		sdlManager.sendRPC(vdRequest);
	}

	/**
	 * Speed情報の往診
	 *
	 */
	private void changeSpeedTextField(Integer first_odo){
		sdlManager.getScreenManager().beginTransaction();

		//テキストを登録する場合
		//sdlManager.getScreenManager().setTextField3("初期走行距離: " + first_odo + "km");

		sdlManager.getScreenManager().commit(new CompletionListener() {
			@Override
			public void onComplete(boolean success) {
				if (success) {
					Log.i(TAG, "change successful");
				}
			}
		});
	}

	/**
	 * 利用可能なテンプレートをチェックする
	 */
	private void checkTemplateType(){

		Object result = sdlManager.getSystemCapabilityManager().getCapability(SystemCapabilityType.DISPLAY);
		if( result instanceof DisplayCapabilities){
			List<String> templates = ((DisplayCapabilities) result).getTemplatesAvailable();

			Log.i("Templete", templates.toString());

		}
	}

	/**
	 * 利用する項目が利用可能かどうか
	 */
	private void checkPermission(){
		List<PermissionElement> permissionElements = new ArrayList<>();

		//チェックを行う項目
		List<String> keys = new ArrayList<>();
		keys.add(GetVehicleData.KEY_ODOMETER);
		permissionElements.add(new PermissionElement(FunctionID.GET_VEHICLE_DATA, keys));

		Map<FunctionID, PermissionStatus> status = sdlManager.getPermissionManager().getStatusOfPermissions(permissionElements);

		//すべてが許可されているかどうか
		Log.i("Permission", "Allowed:" + status.get(FunctionID.GET_VEHICLE_DATA).getIsRPCAllowed());

		//各項目ごとも可能
		//Log.i("Permission", "KEY_RPM　Allowed:" + status.get(FunctionID.GET_VEHICLE_DATA).getAllowedParameters().get(GetVehicleData.KEY_RPM));

	}

	/**
	 * DEFULTテンプレートのサンプル
	 */
	private void setDisplayDefault(){

		sdlManager.getScreenManager().beginTransaction();

		//テキストを登録する場合
		sdlManager.getScreenManager().setTextField1("走行距離: 0km");
		sdlManager.getScreenManager().setTextField2("通算走行距離: 0km");
		setOnTimeSpeedResponse();


		//画像を登録する
		SdlArtwork artwork = new SdlArtwork("girl_1.jpg", FileType.GRAPHIC_JPEG, R.drawable.girl_1, true);

		mp1.start();

		sdlManager.getScreenManager().setPrimaryGraphic(artwork);
		sdlManager.getScreenManager().commit(new CompletionListener() {
			@Override
			public void onComplete(boolean success) {
				if (success) {
					//定期受信用のデータを設定する
					SubscribeVehicleData subscribeRequest = new SubscribeVehicleData();
					subscribeRequest.setOdometer(true);
					subscribeRequest.setOnRPCResponseListener(new OnRPCResponseListener() {
						@Override
						public void onResponse(int correlationId, RPCResponse response) {
							if (response.getSuccess()) {
								Log.i("SdlService", "Successfully subscribed to vehicle data.");
							} else {
								Log.i("SdlService", "Request to subscribe to vehicle data was rejected.");
							}
						}
					});
					sdlManager.sendRPC(subscribeRequest);

				}
			}
		});
	}

}