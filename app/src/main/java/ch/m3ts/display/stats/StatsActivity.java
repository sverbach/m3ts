package ch.m3ts.display.stats;

import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.graphics.drawable.AnimationDrawable;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.widget.FrameLayout;

import cz.fmo.R;

public class StatsActivity extends FragmentActivity {
    private AnimationDrawable animationDrawable;
    private MatchStats stats;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);
        Bundle bundle = getIntent().getExtras();
        FrameLayout relativeLayout = findViewById(R.id.background);
        animationDrawable = (AnimationDrawable) relativeLayout.getBackground();
        animationDrawable.setEnterFadeDuration(2000);
        animationDrawable.setExitFadeDuration(4000);
        animationDrawable.start();
        Fragment nextFragment = new MatchStatsFragment();
        nextFragment.setArguments(bundle);
        FragmentManager manager = getFragmentManager();
        FragmentTransaction transaction = manager.beginTransaction();
        transaction.add(R.id.background, nextFragment);
        transaction.commit();
    }


    public MatchStats getStats() {
        return stats;
    }

    public void setStats(MatchStats stats) {
        this.stats = stats;
    }
}