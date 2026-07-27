package com.lifetrack.app.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stores the user's *overall* monthly budget.
 *
 * Deliberately kept in SharedPreferences rather than Room: AppDatabase is built with
 * fallbackToDestructiveMigration(), so any schema bump wipes user data. Prefs survive that.
 *
 * Stored as paise (Long) to avoid Float precision loss on large budgets.
 */
class BudgetPrefs private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("lifetrack_budget", Context.MODE_PRIVATE)

    private val _overallMonthlyBudget = MutableStateFlow(read())

    /** null = user has not set an overall budget (fall back to sum of category limits). */
    val overallMonthlyBudget: StateFlow<Double?> = _overallMonthlyBudget

    private fun read(): Double? {
        if (!prefs.contains(KEY_OVERALL)) return null
        val paise = prefs.getLong(KEY_OVERALL, 0L)
        return if (paise > 0L) paise / 100.0 else null
    }

    fun setOverallMonthlyBudget(rupees: Double?) {
        val valid = rupees?.takeIf { it > 0.0 && it.isFinite() }
        val editor = prefs.edit()
        if (valid == null) editor.remove(KEY_OVERALL)
        else editor.putLong(KEY_OVERALL, Math.round(valid * 100.0))
        editor.apply()
        _overallMonthlyBudget.value = valid
    }

    companion object {
        private const val KEY_OVERALL = "overall_monthly_budget_paise"

        @Volatile private var instance: BudgetPrefs? = null

        fun get(context: Context): BudgetPrefs =
            instance ?: synchronized(this) {
                instance ?: BudgetPrefs(context.applicationContext).also { instance = it }
            }
    }
}
