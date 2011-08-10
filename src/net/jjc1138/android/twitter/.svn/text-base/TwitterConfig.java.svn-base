package net.jjc1138.android.twitter;

import java.text.ChoiceFormat;
import java.text.MessageFormat;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.net.Uri;
import android.os.Bundle;
import android.os.Vibrator;
import android.text.Editable;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.UnderlineSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.AdapterView.OnItemSelectedListener;

public class TwitterConfig extends Activity {
	final static int[] INTERVALS = { 3, 5, 10, 15, 30, 60, 120 };
	final static int DEFAULT_INTERVAL_INDEX = 4;
	final static String PREFS = "prefs";

	private CheckBox enable;
	private Spinner interval;
	private CheckBox messages;
	private CheckBox replies;
	private CheckBox sound;
	private CheckBox vibrate;
	private CheckBox lights;
	private TextView username;
	private Button sign_in;
	private Spinner filter_type;
	private EditText filter;

	private LinearLayout settings_changed;
	private LinearLayout filter_needed;

	private SharedPreferences prefs;
	private SharedPreferences unsaved;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		
		enable = (CheckBox) findViewById(R.id.enable);
		interval = (Spinner) findViewById(R.id.interval);
		messages = (CheckBox) findViewById(R.id.messages);
		replies = (CheckBox) findViewById(R.id.replies);
		sound = (CheckBox) findViewById(R.id.sound);
		vibrate = (CheckBox) findViewById(R.id.vibrate);
		lights = (CheckBox) findViewById(R.id.lights);
		username = (TextView) findViewById(R.id.username);
		sign_in = (Button) findViewById(R.id.sign_in);
		filter_type = (Spinner) findViewById(R.id.filter_type);
		filter = (EditText) findViewById(R.id.filter);
		
		settings_changed = (LinearLayout) findViewById(R.id.settings_changed);
		filter_needed = (LinearLayout) findViewById(R.id.filter_needed);
		
		// Set up the interval choices:
		final String[] intervalChoiceText = new String[INTERVALS.length];
		for (int i = 0; i < INTERVALS.length; ++i) {
			int option = INTERVALS[i];
			intervalChoiceText[i] = MessageFormat.format(
				new ChoiceFormat(getString(R.string.interval_option))
					.format(option), option);
		}
		ArrayAdapter<String> a = new ArrayAdapter<String>(this,
			android.R.layout.simple_spinner_item, intervalChoiceText);
		a.setDropDownViewResource(
			android.R.layout.simple_spinner_dropdown_item);
		interval.setAdapter(a);
		interval.setSelection(DEFAULT_INTERVAL_INDEX);
		filter_type.setSelection(Fetcher.FILTER_ALL, false);
		
		prefs = getSharedPreferences(PREFS, 0);
		unsaved = getSharedPreferences("unsaved", 0);
		
		// Call settingsChanged() whenever the user does anything:
		CompoundButton.OnCheckedChangeListener checkWatcher =
			new CompoundButton.OnCheckedChangeListener() {
			
				@Override
				public void onCheckedChanged(CompoundButton buttonView,
					boolean isChecked) {
					
					settingsChanged();
				}
			};
		TextWatcher textWatcher = new TextWatcher() {
			@Override
			public void afterTextChanged(Editable s) {
				settingsChanged();
			}
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count,
				int after) {}
			@Override
			public void onTextChanged(CharSequence s, int start, int before,
				int count) {}
		};
		OnItemSelectedListener selectionWatcher = new OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view,
				int position, long id) {
				
				settingsChanged();
			}
		
			@Override
			public void onNothingSelected(AdapterView<?> parent) {}
		};
		
		enable.setOnCheckedChangeListener(checkWatcher);
		interval.setOnItemSelectedListener(selectionWatcher);
		messages.setOnCheckedChangeListener(checkWatcher);
		replies.setOnCheckedChangeListener(checkWatcher);
		sound.setOnCheckedChangeListener(checkWatcher);
		vibrate.setOnCheckedChangeListener(checkWatcher);
		lights.setOnCheckedChangeListener(checkWatcher);
		filter_type.setOnItemSelectedListener(selectionWatcher);
		filter.addTextChangedListener(textWatcher);
		
		// Previews of notification actions:
		sound.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				if (sound.isChecked()) {
					MediaPlayer mp = MediaPlayer.create(
						TwitterConfig.this, R.raw.tweet);
					mp.setOnCompletionListener(new OnCompletionListener() {
						@Override
						public void onCompletion(MediaPlayer mp) {
							mp.release();
						}
					});
					mp.start();
				}
			}
		});
		vibrate.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				if (vibrate.isChecked()) {
					// SRSLY? They couldn't think of a better name for this
					// class?
					((Vibrator) getSystemService(VIBRATOR_SERVICE))
						.vibrate(Fetcher.VIBRATION_PATTERN, -1);
				}
			}
		});
		
		sign_in.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				startActivity(new Intent(
					TwitterConfig.this, TwitterAuth.class));
			}
		});
		
		// Saving and reverting:
		((Button) findViewById(R.id.save)).setOnClickListener(
			new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					uiToPrefs(prefs);
					settingsChanged();
					
					// Trigger the AlarmReceiver instead of starting the service
					// directly so that the wake lock gets acquired.
					// The service will take care of rescheduling itself
					// appropriately.
					sendBroadcast(
						new Intent(TwitterConfig.this, AlarmReceiver.class));
				}
			});
		((Button) findViewById(R.id.revert)).setOnClickListener(
			new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					prefsToUI(prefs);
					settingsChanged();
				}
			});
		
		// The follow link:
		TextView follow_link = (TextView) findViewById(R.id.follow_link);
		Spannable text = (Spannable) follow_link.getText();
		text.setSpan(new UnderlineSpan(), 0, text.length(), 0);
		follow_link.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				startActivity((new Intent(Intent.ACTION_VIEW,
					Uri.parse("http://m.twitter.com/nanoTweeter"))));
			}
		});
		
		// This is here to get the fetching process going after a new version is
		// installed. That's necessary because existing alarms are canceled when
		// a package is uninstalled. Obviously this relies on the user starting
		// the configuration activity after install, but I can't find a more
		// reliable mechanism.
		if (prefs.getBoolean("enable", false)) {
			sendBroadcast(
				new Intent(TwitterConfig.this, AlarmReceiver.class));
		}
		
		if (prefs.getString("password", "").length() > 0) {
			showDialog(DIALOG_UPGRADERS_NEED_TO_SIGN_IN_AGAIN);
		}
	}

	private static final int DIALOG_UPGRADERS_NEED_TO_SIGN_IN_AGAIN = 0;
	private static final int DIALOG_CREDITS = 1;

	@Override
	protected Dialog onCreateDialog(int id) {
		if (id == DIALOG_UPGRADERS_NEED_TO_SIGN_IN_AGAIN) {
			return new AlertDialog.Builder(this)
				.setMessage(R.string.upgraders_need_to_sign_in_again)
				.setPositiveButton(R.string.ok,
					new DialogInterface.OnClickListener() {
				
					@Override
					public void onClick(DialogInterface dialog, int which) {
						startActivity(new Intent(
							TwitterConfig.this, TwitterAuth.class));
					}
				})
				.setNegativeButton(R.string.sign_in_again_later, null)
				.create();
		} else if (id == DIALOG_CREDITS) {
			return new AlertDialog.Builder(this)
				.setMessage(R.string.credits_text)
				.setPositiveButton(R.string.ok, null)
				.create();
		} else {
			assert false;
			return null;
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		menu.add(R.string.credits).setOnMenuItemClickListener(
			new MenuItem.OnMenuItemClickListener() {
		
			@Override
			public boolean onMenuItemClick(MenuItem arg0) {
				showDialog(DIALOG_CREDITS);
				return true;
			}
		});
		return true;
	}

	@Override
	protected void onPause() {
		super.onPause();
		
		if (!isUISavedIn(prefs)) {
			uiToPrefs(unsaved);
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		
		final String examplePref = "enable";
		if (unsaved.contains(examplePref)) {
			prefsToUI(unsaved);
			SharedPreferences.Editor e = unsaved.edit();
			e.clear();
			e.commit();
		} else if (prefs.contains(examplePref)) {
			prefsToUI(prefs);
		} else {
			// Store defaults:
			uiToPrefs(prefs);
		}
		settingsChanged();
	}

	private void settingsChanged() {
		settings_changed.setVisibility(
			isUISavedIn(prefs) ? View.GONE : View.VISIBLE);
		int filterSelected = filter_type.getSelectedItemPosition();
		filter_needed.setVisibility(
			(filterSelected == Fetcher.FILTER_NONE ||
			filterSelected == Fetcher.FILTER_ALL) ?
				View.GONE : View.VISIBLE);
	}

	private void uiToPrefs(SharedPreferences p) {
		SharedPreferences.Editor e = p.edit();
		e.putBoolean("enable", enable.isChecked());
		e.putInt("interval", INTERVALS[interval.getSelectedItemPosition()]);
		e.putBoolean("messages", messages.isChecked());
		e.putBoolean("replies", replies.isChecked());
		e.putBoolean("sound", sound.isChecked());
		e.putBoolean("vibrate", vibrate.isChecked());
		e.putBoolean("lights", lights.isChecked());
		e.putInt("filter_type", filter_type.getSelectedItemPosition());
		e.putString("filter", filter.getText().toString());
		e.commit();
	}

	private int getIntervalIndexOf(int entry) {
		for (int i = 0; i < INTERVALS.length; ++i) {
			if (INTERVALS[i] == entry) {
				return i;
			}
		}
		return DEFAULT_INTERVAL_INDEX;
	}

	private void prefsToUI(SharedPreferences p) {
		enable.setChecked(p.getBoolean("enable", true));
		interval.setSelection(getIntervalIndexOf(
			p.getInt("interval", INTERVALS[DEFAULT_INTERVAL_INDEX])));
		
		String usernameString = p.getString("username", null);
		if (usernameString != null &&
			p.getString("oauth_token_secret", null) != null) {
			
			sign_in.setText(R.string.change_account);
			username.setText(getString(R.string.signed_in_as, usernameString));
			username.setVisibility(View.VISIBLE);
		} else {
			sign_in.setText(R.string.sign_in);
			username.setVisibility(View.GONE);
		}
		
		messages.setChecked(p.getBoolean("messages", true));
		replies.setChecked(p.getBoolean("replies", true));
		sound.setChecked(p.getBoolean("sound", false));
		vibrate.setChecked(p.getBoolean("vibrate", false));
		lights.setChecked(p.getBoolean("lights", false));
		filter_type.setSelection(p.getInt("filter_type", Fetcher.FILTER_ALL));
		filter.setText(p.getString("filter", ""));
	}

	private boolean isUISavedIn(SharedPreferences p) {
		return
			enable.isChecked() == p.getBoolean("enable", true) &&
			INTERVALS[interval.getSelectedItemPosition()] ==
				p.getInt("interval", INTERVALS[DEFAULT_INTERVAL_INDEX]) &&
			messages.isChecked() == p.getBoolean("messages", true) &&
			replies.isChecked() == p.getBoolean("replies", true) &&
			sound.isChecked() == p.getBoolean("sound", false) &&
			vibrate.isChecked() == p.getBoolean("vibrate", false) &&
			lights.isChecked() == p.getBoolean("lights", false) &&
			filter_type.getSelectedItemPosition() ==
				p.getInt("filter_type", Fetcher.FILTER_ALL) &&
			filter.getText().toString().equals(p.getString("filter", ""));
	}
}
