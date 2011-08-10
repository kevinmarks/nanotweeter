package net.jjc1138.android.twitter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// The API documentation says that AlarmManager can only fire broadcasts, not
// start services. Starting services does seem to work sometimes, but it doesn't
// seem to be reliable that the device will stay alive long enough to acquire
// the wake lock.
public class AlarmReceiver extends BroadcastReceiver {

	@Override
	public void onReceive(Context context, Intent intent) {
		FetcherWakeLock.acquire(context);
		context.startService(new Intent(context, Fetcher.class));
	}

}
