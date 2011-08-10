package net.jjc1138.android.twitter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {

	@Override
	public void onReceive(Context context, Intent intent) {
		FetcherWakeLock.acquire(context);
		context.startService(new Intent(context, Fetcher.class));
	}

}
