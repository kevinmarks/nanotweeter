package net.jjc1138.android.twitter;

import oauth.signpost.OAuthConsumer;
import oauth.signpost.OAuthProvider;
import oauth.signpost.commonshttp.CommonsHttpOAuthConsumer;
import oauth.signpost.commonshttp.CommonsHttpOAuthProvider;
import oauth.signpost.exception.OAuthCommunicationException;
import oauth.signpost.exception.OAuthExpectationFailedException;
import oauth.signpost.exception.OAuthMessageSignerException;
import oauth.signpost.exception.OAuthNotAuthorizedException;
import oauth.signpost.http.HttpParameters;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationManager;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class TwitterAuth extends Activity {

	private static final String CONSUMER_KEY = "aey7oorcN2C9HPgwehrxQ";
	private static final String CONSUMER_SECRET = "vtzK8zjyfcZ96UmqohgmyjKsQxKFyCpu5dIaG3XH4s";

	public static CommonsHttpOAuthProvider getOAuthProvider() {
		return new CommonsHttpOAuthProvider(
			"https://api.twitter.com/oauth/request_token",
			"https://api.twitter.com/oauth/access_token",
			"https://api.twitter.com/oauth/authorize");
	}

	public static CommonsHttpOAuthConsumer getOAuthConsumer(Context context) {
		final CommonsHttpOAuthConsumer consumer = new CommonsHttpOAuthConsumer(
			CONSUMER_KEY, CONSUMER_SECRET);
		final SharedPreferences prefs =
			context.getSharedPreferences(TwitterConfig.PREFS, 0);
		consumer.setTokenWithSecret(
			prefs.getString("oauth_token", null),
			prefs.getString("oauth_token_secret", null));
		
		return consumer;
	}

	private ActivityTask<TwitterAuth> task = null;

	@SuppressWarnings("unchecked")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		setContentView(R.layout.auth);
		
		final String callback = "https://auth.nanotweeter.com/success";
		final OAuthProvider provider = getOAuthProvider();
		final OAuthConsumer consumer = getOAuthConsumer(this);
		
		final WebView browser = (WebView) findViewById(R.id.browser);
		{
			final WebSettings settings = browser.getSettings();
			settings.setJavaScriptEnabled(true);
			settings.setSavePassword(false);
			settings.setSaveFormData(false);
		}
		
		browser.setWebViewClient(new WebViewClient() {
		
			@Override
			public void onPageStarted(WebView view, String url, Bitmap favicon) {
				shouldOverrideUrlLoading(view, url);
			}
		
			@Override
			public boolean shouldOverrideUrlLoading(
				WebView view, final String url) {
				
				if (url.startsWith(callback)) {
					browser.setVisibility(View.INVISIBLE);
					browser.stopLoading();
					browser.loadUrl("about:blank");
					
					task = new ActivityTask<TwitterAuth>(TwitterAuth.this) {
					
						@Override
						protected void run() {
							try {
								provider.retrieveAccessToken(consumer,
									Uri.parse(url)
										.getQueryParameter("oauth_verifier"));
							} catch (OAuthMessageSignerException e) {
								throw new RuntimeException(e);
							} catch (OAuthNotAuthorizedException e) {
								throw new RuntimeException(e);
							} catch (OAuthExpectationFailedException e) {
								throw new RuntimeException(e);
							} catch (OAuthCommunicationException e) {
								finish(new Finisher<TwitterAuth>() {
									@Override
									public void finish(TwitterAuth activity) {
										activity.showDialog(
											DIALOG_NETWORK_ERROR);
									}
								});
								return;
							}
							
							SharedPreferences prefs = getSharedPreferences(
								TwitterConfig.PREFS, 0);
							SharedPreferences.Editor editor = prefs.edit();
							HttpParameters p = provider.getResponseParameters();
							
							final String username = p.getFirst("screen_name");
							if (!prefs.getString("username", "").equals(
								username)) {
								
								deleteFile(Fetcher.LAST_TWEET_ID_FILENAME);
							}
							
							editor.putString("username",
								username);
							editor.putString("user_id",
								p.getFirst("user_id"));
							
							editor.putString("oauth_token",
								consumer.getToken());
							editor.putString("oauth_token_secret",
								consumer.getTokenSecret());
							
							// Remove password if it was stored by an old
							// version of the app:
							editor.remove("password");
							
							editor.commit();
							
							finish(new ActivityTask.Finisher<TwitterAuth>() {
								@Override
								public void finish(TwitterAuth activity) {
									((NotificationManager) activity
										.getSystemService(NOTIFICATION_SERVICE))
										.cancel(Fetcher.ERROR_NOTIFICATION_ID);
									activity.finish();
								}
							});
						}
					
						@Override
						protected ProgressDialog makeProgressDialog(
							final TwitterAuth activity) {
							
							final ProgressDialog pd =
								new ProgressDialog(activity);
							pd.setMessage(activity.getString(
								R.string.fetching_access_token));
							pd.setOnCancelListener(
								new DialogInterface.OnCancelListener() {
							
								@Override
								public void onCancel(DialogInterface dialog) {
									activity.showDialog(DIALOG_INCOMPLETE);
								}
							});
							return pd;
						}
					
					};
					
					return true;
				}
				return false;
			}
		
		});
		
		task = (ActivityTask<TwitterAuth>) getLastNonConfigurationInstance();
		if (task != null) {
			task.newActivity(this);
			return;
		}
		
		if (savedInstanceState != null) {
			browser.restoreState(savedInstanceState);
			browser.setVisibility(View.VISIBLE);
			return;
		}
		
		task = new ActivityTask<TwitterAuth>(this) {
		
			@Override
			protected void run() {
				String authorizationURL = null;
				try {
					authorizationURL =
						provider.retrieveRequestToken(consumer, callback);
				} catch (OAuthMessageSignerException e) {
					throw new RuntimeException(e);
				} catch (OAuthNotAuthorizedException e) {
					throw new RuntimeException(e);
				} catch (OAuthExpectationFailedException e) {
					throw new RuntimeException(e);
				} catch (OAuthCommunicationException e) {
					finish(new Finisher<TwitterAuth>() {
						@Override
						public void finish(TwitterAuth activity) {
							activity.showDialog(
								DIALOG_NETWORK_ERROR);
						}
					});
				}
				
				assert authorizationURL != null;
				
				final String fAuthorizationURL = authorizationURL;
				finish(new ActivityTask.Finisher<TwitterAuth>() {
					@Override
					public void finish(TwitterAuth activity) {
						final WebView wv =
							(WebView) activity.findViewById(R.id.browser);
						wv.loadUrl(fAuthorizationURL);
						wv.setVisibility(View.VISIBLE);
						activity.task = null;
					}
				});
			}
		
			@Override
			protected ProgressDialog makeProgressDialog(
				final TwitterAuth activity) {
				
				final ProgressDialog pd = new ProgressDialog(activity);
				pd.setMessage(
					activity.getString(R.string.fetching_request_token));
				pd.setOnCancelListener(new DialogInterface.OnCancelListener() {
					@Override
					public void onCancel(DialogInterface dialog) {
						activity.showDialog(DIALOG_INCOMPLETE);
					}
				});
				return pd;
			}
		
		};
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		((WebView) findViewById(R.id.browser)).saveState(outState);
	}

	@Override
	public Object onRetainNonConfigurationInstance() {
		return task;
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			showDialog(DIALOG_INCOMPLETE);
			return true;
		} else {
			return super.onKeyDown(keyCode, event);
		}
	}

	private static final int DIALOG_INCOMPLETE = 0;
	private static final int DIALOG_NETWORK_ERROR = 1;

	@Override
	protected Dialog onCreateDialog(int id) {
		if (id == DIALOG_INCOMPLETE) {
			return new AlertDialog.Builder(this)
				.setMessage(R.string.auth_not_completed)
				.setPositiveButton(R.string.cancel_auth,
					new DialogInterface.OnClickListener() {
				
					@Override
					public void onClick(DialogInterface dialog, int which) {
						finish();
					}
				})
				.setNegativeButton(R.string.dont_cancel_auth, null)
				.setCancelable(false)
				.create();
		} else if (id == DIALOG_NETWORK_ERROR) {
			return new AlertDialog.Builder(this)
				.setMessage(R.string.auth_network_error)
				.setPositiveButton(R.string.ok,
					new DialogInterface.OnClickListener() {
				
					@Override
					public void onClick(DialogInterface arg0, int arg1) {
						finish();
					}
				})
				.setOnCancelListener(new DialogInterface.OnCancelListener() {
					@Override
					public void onCancel(DialogInterface arg0) {
						finish();
					}
				})
				.create();
		} else {
			assert false;
			return null;
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		
		if (task != null) {
			task.activityDestroyed();
		}
	}

}
