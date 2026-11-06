package ru.pda.tvhome;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Network-constrained daily metadata check; never downloads or installs in the background. */
public final class UpdateCheckJob extends JobService {
    private JobParameters active;
    private android.os.CancellationSignal cancellation;
    @Override public boolean onStartJob(JobParameters params) {
        active=params;
        cancellation=AppUpdates.check(this,true,()->{if(active==params){active=null;cancellation=null;jobFinished(params,false);}});
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { active=null;if(cancellation!=null){cancellation.cancel();cancellation=null;}return true; }
}
