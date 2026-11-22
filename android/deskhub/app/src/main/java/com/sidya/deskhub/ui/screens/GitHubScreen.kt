package com.sidya.deskhub.ui.screens

import android.view.View
import com.sidya.deskhub.data.DashState
import com.sidya.deskhub.ui.MainActivity
import com.sidya.deskhub.ui.Pal

class GitHubScreen(host: MainActivity) : Screen(host) {
    override fun render(s: DashState): View = ui.vbox {
        addView(ui.card("Repository", tag = "gh_repo") {
            val g = s.github
            addView(ui.text(app.settings.githubRepo, 15f, bold = true))
            if (g == null) empty(s.githubError ?: "Loading…")
            else {
                g.optString("description").takeIf { it.isNotEmpty() && it != "null" }?.let { addView(ui.text(it, 12f, Pal.dim)) }
                addView(ui.row("Stars", g.optInt("stargazers_count").toString()))
                addView(ui.row("Forks", g.optInt("forks_count").toString()))
                addView(ui.row("Open issues", g.optInt("open_issues_count").toString()))
                addView(ui.row("Default branch", g.optString("default_branch")))
                addView(ui.row("Last push", g.optString("pushed_at").replace('T', ' ').removeSuffix("Z")))
                addView(ui.row("Visibility", if (g.optBoolean("private")) "private" else "public"))
                s.githubError?.let { addView(ui.text(it, 11f, Pal.warn)) }
            }
            addView(ui.button("Refresh", primary = false) { app.repo.refreshInternet() }, ui.lp(top = 8))
        })
        val c = section(s, "github")
        addView(ui.card("Contributions", tag = "gh_contrib") {
            if (c == null) { empty("Contribution stats come from the PC companion's GitHub integration (optional token on the PC)."); return@card }
            addView(ui.row("User", c.optString("login")))
            addView(ui.row("This year", c.optInt("total").toString()))
            addView(ui.row("Today", c.optInt("today").toString()))
            addView(ui.row("This week / last week", "${c.optInt("week")} / ${c.optInt("last_week")}"))
            addView(ui.row("Current streak", "${c.optInt("streak")} days"))
            addView(ui.row("Longest streak", "${c.optInt("longest")} days"))
        })
    }
}
