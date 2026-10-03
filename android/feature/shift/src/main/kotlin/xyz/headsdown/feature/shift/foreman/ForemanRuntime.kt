package xyz.headsdown.feature.shift.foreman

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.headsdown.ml.ForemanModels
import xyz.headsdown.ml.PickupClassifier
import xyz.headsdown.ml.ShiftPlanner
import xyz.headsdown.ml.pickup.FailClosedPickupClassifier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Foreman models as the shift service uses them: read once from the APK's assets (signed
 * with it; nothing is downloaded), on first use, by whichever thread asks first (the service
 * asks from its background thread).
 *
 * Loading can only fail safe:
 * - no pickup model, for any reason, gives the fail-closed classifier: every motion of a hot
 *   rig breaks the shift;
 * - no planner parameters gives the built-in defaults, which never call a window confident.
 */
@Singleton
class ForemanRuntime internal constructor(
    loadPickup: () -> PickupClassifier,
    loadPlanner: () -> ShiftPlanner,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        { ForemanModels.pickupClassifier(context.assets) },
        { ForemanModels.shiftPlanner(context.assets) },
    )

    internal val pickupClassifier: PickupClassifier by lazy {
        try {
            loadPickup()
        } catch (_: RuntimeException) {
            FailClosedPickupClassifier
        }
    }

    internal val shiftPlanner: ShiftPlanner by lazy {
        try {
            loadPlanner()
        } catch (_: RuntimeException) {
            ForemanModels.shiftPlanner(null as String?)
        }
    }

    /** The pickup model in use (`pickup_logistic`, or `fail-closed` when none could be loaded). Loads it if needed. */
    val pickupModelName: String get() = pickupClassifier.modelName

    /** False when no pickup model could be loaded and every motion of a hot rig will break the shift. */
    val pickupModelLoaded: Boolean get() = pickupClassifier !== FailClosedPickupClassifier
}
