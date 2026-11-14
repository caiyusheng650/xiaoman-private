package dev.xiaoman.app.lounge

import android.app.job.JobParameters
import android.app.job.JobService
import dev.xiaoman.app.XiaomanApp
import kotlinx.coroutines.Job

class LoungeJobService : JobService() {
    private var active: JobParameters? = null
    private var round: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val lounge = (application as XiaomanApp).graph.lounge
        active = params
        round = lounge.runAutomatic {
            if (active === params) {
                active = null
                round = null
                jobFinished(params, false)
            }
        }
        if (round == null) active = null
        return round != null
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (active === params) {
            active = null
            round?.cancel()
            round = null
        }
        return false
    }
}
