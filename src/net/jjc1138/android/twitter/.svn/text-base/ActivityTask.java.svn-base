package net.jjc1138.android.twitter;

import android.app.Activity;
import android.app.ProgressDialog;
import android.os.Handler;
import android.os.SystemClock;

/**
 * <code>ActivityTask</code> is a utility for managing a background thread that
 * interacts with an <code>Activity</code>. It allows the thread to survive the
 * activity being destroyed and recreated on configuration changes.
 * 
 * To use <code>ActivityTask</code>:
 * 
 * 1) Create a class extending <code>ActivityTask&lt;A&gt;</code>, where A is
 *    the Activity that the task will work with. Override <code>run()</code> and
 *    in that method do whatever should be done in the background. You can also
 *    optionally override <code>makeProgressDialog()</code> to return a
 *    <code>ProgressDialog</code> that should be displayed while the task is
 *    running. It is crucial that <code>run()</code> must not attempt to access
 *    the <code>ProgressDialog</code>, the activity UI, or any other part of the
 *    system that is accessed through <code>Activity</code>, because the
 *    activity may be destroyed at any time while the task is executing.
 *    
 *    At the end of your <code>run()</code> method you will want to update the
 *    UI. To do that you must call the <code>ActivityTask</code> instance's
 *    <code>finish()</code> method, passing it an
 *    <code>ActivityTask.Finisher</code> instance. The finisher's
 *    <code>finish()</code> method will be called with an instance of your
 *    activity as a parameter. That activity instance may be the original
 *    activity that started the task, or it may be a new one if there were
 *    configuration changes since the task was started.
 *    
 *    <strong>Caution:</strong>
 *    It is crucially important that you only access the activity through the
 *    parameter, and not via any implicit access that you might have to the
 *    original activity if you define your <code>ActivityTask</code> as an inner
 *    class inside your activity class. It can be helpful to temporarily change
 *    your overridden methods to be static, and check the compiler errors to see
 *    if you have inadvertently used any implicit access.
 * 
 * 2) Add an instance variable to your <code>Activity</code> to store an
 *    instance of <code>ActivityTask&lt;A&gt;</code> (again, where A is your
 *    activity class).
 * 
 * 3) In your activity's <code>onCreate()</code>, retrieve any running task by
 *    using <code>getLastNonConfigurationInstance()</code> and storing the
 *    result into the instance variable created in step 2. If it is not null
 *    then call <code>newActivity(this)</code> on it.
 * 
 * 4) In your activity's <code>onDestroy()</code>, call
 *    <code>activityDestroyed()</code> on your <code>ActivityTask</code>
 *    instance if it is not null.
 * 
 * 5) Override <code>onRetainNonConfigurationInstance()></code> and return the
 *    instance variable defined in step 2. 
 * 
 * 6) When you want to start your task create a new instance of the class you
 *    created in step 1 and assign it to the instance variable defined in step
 *    2. Creating the instance will immediately start the background task and
 *    display the progress dialog if you defined one.
 */
public abstract class ActivityTask<A extends Activity> {

	protected ProgressDialog makeProgressDialog(A activity) { return null; }
	protected abstract void run();

	public interface Finisher<A extends Activity> {
		public void finish(A activity);
	}

	protected final void finish(final Finisher<A> finisher) {
		new Thread() {
			@Override
			public void run() {
				long start = SystemClock.uptimeMillis();
				while (true) {
					// The Activity may be destroyed when we get here. If so,
					// keep retrying until we have a new one:
					try {
						final Handler h = handler;
						h.post(new Runnable() {
							@Override
							public void run() {
								if (activity == null || done) {
									return;
								}
								if (h != handler) {
									// The activity was destroyed and recreated
									// in between the time when this Runnable
									// was posted and the time when it started
									// running, so we are running on the UI
									// thread of the now-destroyed activity.
									// (As far as I know the UI thread doesn't
									// change when an activity is recreated, so
									// this condition should be impossible to
									// reach, but better safe than sorry.)
									return;
								}
								// We are now on the UI thread with an Activity
								// that hasn't been destroyed, so we can
								// guarantee that it won't be destroyed while
								// this method is running (since destruction has
								// to happen on the UI thread).
								done = true;
								if (progress != null) {
									progress.dismiss();
								}
								finisher.finish(activity);
								activity = null;
								progress = null;
								handler = null;
							}
						});
					} catch (NullPointerException e) {
						// handler can be null if the Activity was just
						// destroyed.
					}
					if (done || SystemClock.uptimeMillis() - start > timeout) {
						// It is not guaranteed that
						// Activity.getLastNonConfigurationInstance() will
						// return what was returned from
						// Activity.onRetainNonConfigurationInstance(), so our
						// newActivity() may never be called. In that case we
						// simply give up after a reasonable timeout period.
						break;
					}
					Thread.yield();
				}
			}
		}.start();
	}

	public ActivityTask(A activity) {
		newActivity(activity);
		new Thread() {
			@Override
			public void run() {
				ActivityTask.this.run();
			}
		}.start();
	}

	public final void newActivity(A activity) {
		if (done) {
			return;
		}
		this.activity = activity;
		handler = new Handler();
		progress = makeProgressDialog(activity);
		if (progress != null) {
			progress.show();
		}
	}

	public final void activityDestroyed() {
		if (progress != null) {
			progress.dismiss();
			progress = null;
		}
		activity = null;
		handler = null;
	}

	public final boolean isDone() {
		return done;
	}

	private static final long timeout = 10000;
	private volatile boolean done = false;
	private A activity;
	private ProgressDialog progress;
	private Handler handler;

}
