package net.jjc1138.android.twitter;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import oauth.signpost.commonshttp.CommonsHttpOAuthConsumer;
import oauth.signpost.exception.OAuthCommunicationException;
import oauth.signpost.exception.OAuthExpectationFailedException;
import oauth.signpost.exception.OAuthMessageSignerException;

import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.JsonReader;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import android.support.v4.app.*;

public class Fetcher extends Service {
	final static String LOG_TAG = "nanoTweeter";

	final static int FILTER_NONE = 0;
	final static int FILTER_WHITELIST = 1;
	final static int FILTER_BLACKLIST = 2;
	final static int FILTER_ALL = 3;

	final static String LAST_TWEET_ID_FILENAME = "lasttweets";
	final static long[] VIBRATION_PATTERN = new long[] { 0, 100, 60, 100 };
	final static int ERROR_NOTIFICATION_ID = 0;
	final static int DM_REAUTH_NOTIFICATION_ID = 1;
	private final static String TWEET_SOUND_FILENAME = "tweet.ogg";

	private SharedPreferences prefs;

	private FetcherThread fetcherThread;
	private Handler handler;

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		
		prefs = getSharedPreferences(TwitterConfig.PREFS, 0);
		handler = new Handler();
		   try {
	        Context context = getApplicationContext();
			File httpCacheDir = new File(context.getCacheDir(), "http");
	           long httpCacheSize = 10 * 1024 * 1024; // 10 MiB
	           Class.forName("android.net.http.HttpResponseCache")
	                   .getMethod("install", File.class, long.class)
	                   .invoke(null, httpCacheDir, httpCacheSize);
		   }
	        catch (Exception httpResponseCacheNotAvailable) {
	       }
	}

	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		
		Log.d(LOG_TAG, "Service started.");
		
		if (!prefs.getBoolean("enable", false)) {
			Log.d(LOG_TAG, "Fetching disabled.");
			stopIfIdle();
			return;
		}
		
		final AlarmManager am =
			((AlarmManager) getSystemService(ALARM_SERVICE));
		final PendingIntent operation = PendingIntent.getBroadcast(this, 0,
			new Intent(this, AlarmReceiver.class), 0);
		final int interval = prefs.getInt("interval",
			TwitterConfig.INTERVALS[TwitterConfig.DEFAULT_INTERVAL_INDEX]);
		if (interval == 15 || interval == 30 || interval == 60) {
			final long inexactInterval;
			if (interval == 15) {
				inexactInterval = AlarmManager.INTERVAL_FIFTEEN_MINUTES;
			} else if (interval == 30) {
				inexactInterval = AlarmManager.INTERVAL_HALF_HOUR;
			} else if (interval == 60) {
				inexactInterval = AlarmManager.INTERVAL_HOUR;
			} else {
				assert false;
				inexactInterval = AlarmManager.INTERVAL_HOUR;
			}
			am.setInexactRepeating(
				AlarmManager.ELAPSED_REALTIME_WAKEUP,
				SystemClock.elapsedRealtime() +
					60 * 1000,
				inexactInterval,
				operation);
		} else {
			am.set(
				AlarmManager.ELAPSED_REALTIME_WAKEUP,
				SystemClock.elapsedRealtime() +
					interval * 60 * 1000,
				operation);
		}
		Log.d(LOG_TAG, "Scheduled next run.");
		
		if (fetcherThread != null && fetcherThread.inProgress()) {
			Log.d(LOG_TAG, "Refusing to start another fetcher " +
				"because one is in progress.");
			return;
		} else {
			fetcherThread = new FetcherThread();
			fetcherThread.start();
		}
	}

	private class FetcherThread extends Thread {
		private final Charset FILE_CHARSET = Charset.forName("US-ASCII");
	
		private static final String API_HOST = "api.twitter.com";
		private static final int API_PORT = 443;
		private static final String API_ROOT =
			"https://" + API_HOST + ":" + API_PORT + "/1.1/";
	
		private static final int FIRST_RUN_NOTIFICATIONS = 3;
		// This should be 200, but firing so many notifications causes a memory
		// exhaustion that crashes the phone:
		private static final int MAX_NOTIFICATIONS = 100;
	
		// stopIfIdle() below needs to know if this thread is still doing work.
		// You might think that we could use Thread.isAlive() to find that out,
		// but that wouldn't work because stopIfIdle() is queued to be called
		// from this thread, and it would be possible for stopIfIdle() to start
		// before this thread had actually died. For that reason we use this
		// variable so that the thread can indicate its impending death before
		// queuing the call to stopIfIdle().
		private volatile boolean inProgress = true;
	
		public boolean inProgress() {
			return inProgress;
		}
	
		private void finish(HttpEntity ent) {
			if (ent != null) {
				try {
					ent.consumeContent();
				} catch (IOException e) {
				}
			}
		}
	
		private class DownloadException extends Exception {
			private static final long serialVersionUID = 1L;
		}
		private class HttpErrorException extends DownloadException {
			private static final long serialVersionUID = 1L;
		
			private int status;
		
			public HttpErrorException(final int status) {
				this.status = status;
			}
		
			public int getStatus() {
				return status;
			}
		}

		private void showUnauthorizedNotification(int status) {
			Notification n = new Notification();
			n.icon = R.drawable.notification_icon_status_bar;
			String msg = getString(R.string.unauthorized);
			if (status == HttpStatus.SC_FORBIDDEN ) {
				msg  = getString(R.string.unauthorized);
			} else {
				msg = String.format("HTTP Error %d: Twitter finally killed us" , status);
			}
			Intent i = new Intent(
				Fetcher.this, TwitterAuth.class);
			i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			n.setLatestEventInfo(Fetcher.this, getString(R.string.app_name), msg, PendingIntent.getActivity(Fetcher.this, 0, i, 0));
			n.flags |= Notification.FLAG_AUTO_CANCEL;
			((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
				.notify((status == HttpStatus.SC_FORBIDDEN ) ? DM_REAUTH_NOTIFICATION_ID : ERROR_NOTIFICATION_ID, n);
		}
	
		private HttpEntity download(
			DefaultHttpClient client, CommonsHttpOAuthConsumer consumer,
			URI uri) throws DownloadException {
			
			HttpResponse r = null;
			try {
				final HttpGet get = new HttpGet(uri);
				consumer.sign(get);
				r = client.execute(get);
			} catch (ClientProtocolException e) {
				assert false;
				throw new DownloadException();
			} catch (IOException e) {
				throw new DownloadException();
			} catch (OAuthMessageSignerException e) {
				throw new RuntimeException(e);
			} catch (OAuthExpectationFailedException e) {
				throw new RuntimeException(e);
			} catch (OAuthCommunicationException e) {
				throw new DownloadException();
			}
			
			int status = r.getStatusLine().getStatusCode();
			HttpEntity ent = r.getEntity();
			
			if (status == HttpStatus.SC_UNAUTHORIZED || status == HttpStatus.SC_GONE) {
				showUnauthorizedNotification(status);
				
				finish(ent);
				throw new HttpErrorException(status);
			} else if (status == HttpStatus.SC_FORBIDDEN) {
				// this likely means we are in the "authed old style and are being denied DM's state"
				// pop a dialog suggesting a reAuth - don't kill the whole thing
				showUnauthorizedNotification(status);
				finish(ent);
				return null;
			} else {
				((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
					.cancel(ERROR_NOTIFICATION_ID);
			}
			
			if (status == HttpStatus.SC_OK) {
				// Coolness.
				return ent;
			} else if (status == HttpStatus.SC_NOT_MODIFIED) {
				// Nothing new.
				finish(ent);
				return null;
			}  else {
				// All other response codes are essentially transient errors.
				// "404 Not Found" is an exceptions, but there
				// isn't anything reasonable we can do here to recover from
				// them.
				finish(ent);
				throw new HttpErrorException(status);
			}
		}
	
		private String newLinesToSpaces(String s) {
			return s.replace('\n', ' ');
		}
	
		// You might think that lastFriendStatus and lastReply could be merged
		// into one variable, but that would cause a race because when we're
		// checking the replies a new friend status could be posted with an ID
		// less than the newest reply. That status would then not be fetched on
		// the next update.
		long lastFriendStatus = 1;
		long lastMessage = 1;
		long lastReply = 1;
	
		@SuppressLint("SimpleDateFormat")
		public void fetch() throws DownloadException {
			final CommonsHttpOAuthConsumer consumer =
				TwitterAuth.getOAuthConsumer(Fetcher.this);
			
			if (consumer.getTokenSecret() == null) {
				Log.d(LOG_TAG,
					"Skipping fetch because we are not authenticated.");
				
				if (prefs.getString("password", "").length() != 0) {
					showUnauthorizedNotification(HttpStatus.SC_UNAUTHORIZED);
				}
				return;
			}
			
			{
				BufferedReader br = null;
				try {
					br = new BufferedReader(new InputStreamReader(
						openFileInput(LAST_TWEET_ID_FILENAME), FILE_CHARSET),
						32);
					lastFriendStatus = Long.parseLong(br.readLine());
					lastMessage = Long.parseLong(br.readLine());
					lastReply = Long.parseLong(br.readLine());
				} catch (IOException e) {
				} catch (NumberFormatException e) {
				} finally {
					if (br != null) {
						try {
							br.close();
						} catch (IOException e) {
						}
					}
				}
			}
			
			final String username = prefs.getString("username", "");
			
			abstract class Tweet {
				public Tweet(
					long id, Date date, String name, String screenName, String text, String photo) {
					
					this.id = id;
					this.date = date;
					this.name = name;
					this.screenName = screenName;
					this.text = text;
					this.photo = photo;
				}
			
				public long getID() {
					return id;
				}
				public Date getDate() {
					return date;
				}
				public String getScreenName() {
					return screenName;
				}
				public String getName() {
					return name;
				}
				public String getText() {
					return text;
				}
			
				public String getPhoto() {
					return photo;
				}
				abstract public String toString();
			
				private long id;
				private Date date;
				private String name;
				private String screenName;
				private String text;
				private String photo;
			}
			
			class Status extends Tweet {
				public Status(
					long id, Date date, String name, String screenName, String text, String photo) {
					
					super(id, date, name, screenName, text, photo);
				}
			
				public String toString() {
					return "[" + getScreenName() + "]: " + getText();
				}
			}
			class Message extends Tweet {
				public Message(
					long id, Date date, String name, String screenName, String text, String photo) {
					
					super(id, date, name, screenName, text, photo);
				}
			
				public String toString() {
					return "M[" + getScreenName() + "]: " + getText();
				}
			}
			
			final DateFormat twitterDateFormat = new SimpleDateFormat(
				"E MMM dd HH:mm:ss Z yyyy");
			final LinkedList<Tweet> tweets = new LinkedList<Tweet>();
			
			class TweetParser {
				// returns max id found
				public long tweetsFromJson(InputStreamReader is, long lastMaxId) throws DownloadException {
					long maxId = lastMaxId;
						JsonReader jreader = new JsonReader(is);
						try {
							jreader.beginArray();
							while (jreader.hasNext()){
								
								Date createdAt= new Date(System.currentTimeMillis());;
								long id = -1;
								String text = null;
								String fullname = null;
								String screenName = null;
								String photo = null;

								jreader.beginObject();
								while (jreader.hasNext()){
									String name = jreader.nextName();
									if (name.equals("id")) {
								         id = jreader.nextLong();
								         maxId = Math.max(maxId, id);
								       } else if (name.equals("created_at")) {
								    	   try {
												createdAt = twitterDateFormat.parse(jreader.nextString());
											} catch (ParseException e) {
												createdAt = new Date(System.currentTimeMillis());
											}								       
								       } else if (name.equals("text")) {
								         text = jreader.nextString();
								       } else if (name.equals("user")) {
								         jreader.beginObject();
								         while (jreader.hasNext()) {
									         String uname = jreader.nextName();
									         if (uname.equals("name")) {
									        	 fullname = jreader.nextString();
									         } else if (uname.equals("screen_name")) {
									        	 screenName = jreader.nextString();
									         } else if (uname.equals("profile_image_url")) {
									        	 photo = jreader.nextString();
									         } else {
									        	 jreader.skipValue();
									         }
								         }
								         jreader.endObject();
								         if (!screenName.equals(username)) { 
											Status s = new Status(
														id, createdAt, fullname, screenName, text, photo);
											tweets.addFirst(s);
								         }
								       } else {
								         jreader.skipValue();
								       }
								}
								jreader.endObject();
								
							}
							jreader.endArray();
						jreader.close();
						}
						catch (IOException e)	
						{
							throw new DownloadException();
						}
					
				return maxId;	
				}
			
			}
			TweetParser tp = new TweetParser();
			DefaultHttpClient client = new DefaultHttpClient();
			try {
				client.getParams().setParameter("http.useragent",
					"nanoTweeter/" + getPackageManager().getPackageInfo(
						getPackageName(), 0).versionName);
			} catch (NameNotFoundException e) {
				assert false;
			}
			
			final boolean firstRun = (lastFriendStatus < 2);
			final int filterType = prefs.getInt(
				"filter_type", FILTER_ALL);
			HttpEntity ent = null;
			try {
				if (filterType != FILTER_ALL) {
					ent = download(client, consumer, new URI(API_ROOT +
						"statuses/home_timeline.json" + "?" +
						"include_rts=true&include_entities=true&" +
						(firstRun ? "" :
							("since_id=" + lastFriendStatus + "&")) +
						"count=" + ((firstRun && filterType == FILTER_NONE) ?
							FIRST_RUN_NOTIFICATIONS : MAX_NOTIFICATIONS)));
					if (ent != null) {
						lastFriendStatus = tp.tweetsFromJson(new InputStreamReader(ent.getContent(), "UTF-8"), lastFriendStatus);
					}
						String[] trackWords = prefs.getString("track", "").trim().split(" ");
						Pattern trackpat = null;
						if (trackWords.length >0) {
							String patternString = "\\b(" + StringUtils.join(trackWords, "|") + ")\\b";
							trackpat = Pattern.compile(patternString);
						}
						if (filterType != FILTER_NONE) {
							String[] filterNames =
								prefs.getString("filter", "").split(" ");
							// Sort so that we can use binary search in a
							// moment:
							Arrays.sort(filterNames);
							
							for (Iterator<Tweet> i = tweets.iterator();
								i.hasNext();) {
								final Tweet t = i.next();
								final String screenName =
									t.getScreenName();
								boolean tracked = false;
								if (trackpat != null) {
									final String text = t.getText();
									Matcher matcher = trackpat.matcher(text);
									tracked = matcher.find();
								}
								final boolean filtered = Arrays.binarySearch(
									filterNames, screenName,
									String.CASE_INSENSITIVE_ORDER) >= 0;
								if (filterType == FILTER_WHITELIST) {
									if (!filtered && !tracked) {
										i.remove();
									}
								} else {
									assert filterType == FILTER_BLACKLIST;
									if (filtered) {
										i.remove();
									}
								}
							}
						}
					}
				
				
				if (prefs.getBoolean("messages", false)) {
					ent = download(client, consumer, new URI(API_ROOT +
						"direct_messages.json" + "?include_rts=false&" +
						(firstRun ? "" : ("since_id=" + lastMessage )) ));
					if (ent != null) {
						((NotificationManager) getSystemService(
							NOTIFICATION_SERVICE))
								.cancel(DM_REAUTH_NOTIFICATION_ID);
						lastMessage = tp.tweetsFromJson(new InputStreamReader(ent.getContent(), "UTF-8"), lastMessage);
					}
				}
				
				if (prefs.getBoolean("replies", false)) {
					ent = download(client, consumer, new URI(API_ROOT +
						"statuses/mentions_timeline.json" + "?" +
						"include_rts=false&include_entities=true&" +
						(firstRun ? "" : ("since_id=" + lastReply )) ));
					if (ent != null) {
						lastReply = tp.tweetsFromJson(new InputStreamReader(ent.getContent(), "UTF-8"), lastReply);
					}
				}

			} catch (IOException e) {
				throw new DownloadException();
			} catch (URISyntaxException e) {
				assert false;
				throw new DownloadException();
			} finally {
				finish(ent);
			}
			
			Collections.sort(tweets, new Comparator<Tweet>() {
				@Override
				public int compare(Tweet t1, Tweet t2) {
					return t1.getDate().compareTo(t2.getDate());
				}
			});
			
			final int limit =
				firstRun ? FIRST_RUN_NOTIFICATIONS : MAX_NOTIFICATIONS;
			int nTweets;
			for (nTweets = tweets.size(); nTweets > limit; --nTweets) {
				tweets.removeFirst();
			}
			if (nTweets == 0) {
				return;
			}
			
			final String twitterRoot = "http://twitter.com/";
			final DateFormat df = DateFormat.getTimeInstance(DateFormat.SHORT);
			final NotificationManager nm =
				(NotificationManager) getSystemService(NOTIFICATION_SERVICE);
			final boolean sound = prefs.getBoolean("sound", false);
			Uri notificationSound = null;
			if (sound) {
				notificationSound = Uri.fromFile(
					getFileStreamPath(TWEET_SOUND_FILENAME));
				boolean exists = false;
				for (String i : fileList()) {
					if (i.equals(TWEET_SOUND_FILENAME)) {
						exists = true;
						break;
					}
				}
				if (!exists) {
					// This should only ever have to be done once per
					// installation. If the file is ever changed then the
					// filename must be changed ("tweet2.ogg" or whatever), and
					// you must delete the files from old versions here.
					try {
						InputStream ris = getResources().openRawResource(
							R.raw.tweet);
						FileOutputStream fos = openFileOutput(
							TWEET_SOUND_FILENAME, MODE_WORLD_READABLE);
						byte[] buffer = new byte[8192];
						int read;
						while ((read = ris.read(buffer)) > 0) {
							fos.write(buffer, 0, read);
						}
						ris.close();
						fos.close();
					} catch (IOException e) {
						deleteFile(TWEET_SOUND_FILENAME);
					}
				}
			}
			final boolean vibrate = prefs.getBoolean("vibrate", false);
			final boolean lights = prefs.getBoolean("lights", false);
			
			for (final Tweet t : tweets) {
				final String screenName = t.getScreenName();
				final String name = t.getName();
				final long id = t.getID();
				final boolean message = t instanceof Message;
				Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(
					twitterRoot + (message ? "messages" :
					URLEncoder.encode(screenName) + "/status/" + id)));
				i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				Intent retweet = new Intent(Intent.ACTION_VIEW, Uri.parse("https://twitter.com/intent/retweet?tweet_id=" + id) )
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				final String text = t.getText();
				final Date d = t.getDate();
									
				Bitmap bigIcon = null;
				    try {
				        URL url = new URL(t.getPhoto().replace("normal", "bigger"));
				        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
				        connection.setDoInput(true);
				        connection.connect();
				        InputStream input = connection.getInputStream();
				        bigIcon = BitmapFactory.decodeStream(input);
				    } catch (IOException e) {
				    	Log.e(LOG_TAG, "Couldn't get image.");
				    }
				
				Notification n = new NotificationCompat.Builder(getApplicationContext())
		         .setContentTitle(name + " @"+ screenName +" "+ (message? "(direct)":""))
		         .setContentText(text)
		         .setSmallIcon(R.drawable.notification_icon_status_bar)
		         .setLargeIcon(bigIcon)
		         .setWhen(d.getTime())
		         .setContentIntent(PendingIntent.getActivity(Fetcher.this, 0, i, 0))
		         .addAction(R.drawable.notification_icon_status_bar, getString(R.string.retweet), PendingIntent.getActivity(Fetcher.this, 0, retweet, 0))
		         .setStyle(new NotificationCompat.BigTextStyle()
		         	.bigText(text))
		         .build(); 

				if (true) {
					// handmade layouts win on 4.1 too
					final RemoteViews v = new RemoteViews(
							getPackageName(), (text.length() > 80) ?
							R.layout.notification_longtext : R.layout.notification); 
					if (bigIcon != null ) {
						// setting a null bitmap crashes on gingerbread and probably earlier (Thanks @misc)
						v.setImageViewBitmap(R.id.notification_icon, bigIcon);
					}
					// omit icon for Honeycomb + Ice Cream Sandwich as it displays the LargeIcon above too.
					if (Build.VERSION.SDK_INT < 16 /* JELLY_BEAN */ 
							&& Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB)  {
						v.setViewVisibility(R.id.notification_icon, View.GONE);
					}
					v.setTextViewText(R.id.notification_time, df.format(d));
					v.setTextViewText(R.id.notification_name, name);
					v.setTextViewText(R.id.notification_user, "@" + screenName);
					v.setViewVisibility(R.id.notification_is_message,
							message ? View.VISIBLE : View.GONE);
					v.setTextViewText(R.id.notification_text, text);
					n.contentView = v;
				}
				n.when = d.getTime();
				if (sound) {
					n.audioStreamType = AudioManager.STREAM_RING;
					n.sound = notificationSound;
				}
				if (vibrate) {
					n.vibrate = VIBRATION_PATTERN;
				}
				if (lights) {
					n.flags |= Notification.FLAG_SHOW_LIGHTS;
					n.ledOnMS = 1000;
					n.ledOffMS = 2000;
					n.ledARGB = 0xFF00007F;
				}
				n.flags |= Notification.FLAG_AUTO_CANCEL;
				
				nm.notify((int) (id % Integer.MAX_VALUE), n);
			}
			
			{
				OutputStreamWriter osw = null;
				try {
					osw = new OutputStreamWriter(
						openFileOutput(LAST_TWEET_ID_FILENAME, 0),
						FILE_CHARSET);
					osw.write(
						Long.toString(lastFriendStatus) + "\n" +
						Long.toString(lastMessage) + "\n" +
						Long.toString(lastReply) + "\n");
				} catch (IOException e) {
					// This is a fairly big problem, because we'll keep
					// notifying the user about the same tweets, but I don't
					// think that there's anything sensible we can do about it.
					// An IOException here probably indicates a critical lack of
					// filesystem space.
					Log.e(LOG_TAG, "Couldn't write last tweet ID.");
				} finally {
					if (osw != null) {
						try {
							osw.close();
						} catch (IOException e) {
						}
					}
				}
			}
		}
	
		@Override
		public void run() {
			try {
				fetch();
			} catch (DownloadException e) {
				Log.v(LOG_TAG, "A download failed.");
			} finally {
				// This is in a finally because if there was an unexpected
				// runtime exception and stopIfIdle() wasn't called then the
				// wake lock would be held forever. That would suck.
				inProgress = false;
				handler.post(new Runnable() {
					@Override
					public void run() {
						stopIfIdle();
					}
				});
			}
		}
	}

	private void stopIfIdle() {
		if (fetcherThread != null && fetcherThread.inProgress()) {
			// This could happen if this call to stopIfIdle() gets queued after
			// another starting of the service (which would fire up another
			// thread).
			Log.d(LOG_TAG, "Not stopping service because thread is running.");
			return;
		}
		FetcherWakeLock.release();
		Log.d(LOG_TAG, "Stopping service.");
		stopSelf();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		
		Log.d(LOG_TAG, "Service destroyed.");
	}
}
