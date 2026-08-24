package com.anezium.rokidbus.plugin.foodlog

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MealType as HealthMealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/**
 * Writes a Food Log entry only after the caller has obtained an explicit user opt-in.
 *
 * The caller owns both presenting Health Connect's permission UI and persisting the opt-in;
 * this bridge deliberately has no background or automatic synchronization path.
 */
internal class FoodLogHealthConnectBridge(
    context: Context,
    private val clientFactory: (Context) -> HealthConnectClient = HealthConnectClient::getOrCreate,
) {
    private val appContext = context.applicationContext

    fun availability(): FoodLogHealthConnectAvailability = when (
        HealthConnectClient.getSdkStatus(appContext)
    ) {
        HealthConnectClient.SDK_AVAILABLE -> FoodLogHealthConnectAvailability.Available
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            FoodLogHealthConnectAvailability.ProviderUpdateRequired
        else -> FoodLogHealthConnectAvailability.Unavailable
    }

    suspend fun hasWriteNutritionPermission(): Boolean {
        if (availability() != FoodLogHealthConnectAvailability.Available) return false
        return clientFactory(appContext)
            .permissionController
            .getGrantedPermissions()
            .contains(WRITE_NUTRITION_PERMISSION)
    }

    /** Uses a stable client record ID and a monotonic version so meal edits replace prior writes. */
    suspend fun syncEntry(
        entry: FoodEntry,
        userOptedIn: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): FoodLogHealthConnectSyncResult {
        if (!userOptedIn) return FoodLogHealthConnectSyncResult.NotOptedIn
        when (val status = availability()) {
            FoodLogHealthConnectAvailability.Available -> Unit
            else -> return FoodLogHealthConnectSyncResult.Unavailable(status)
        }

        val client = try {
            clientFactory(appContext)
        } catch (exception: Exception) {
            return FoodLogHealthConnectSyncResult.Failed(exception.safeMessage())
        }
        val hasPermission = try {
            client.permissionController.getGrantedPermissions().contains(WRITE_NUTRITION_PERMISSION)
        } catch (exception: Exception) {
            return FoodLogHealthConnectSyncResult.Failed(exception.safeMessage())
        }
        if (!hasPermission) return FoodLogHealthConnectSyncResult.PermissionRequired

        return try {
            client.insertRecords(
                listOf(entry.toNutritionRecord(zoneId, syncVersionMillis = nextHealthConnectVersion())),
            )
            FoodLogHealthConnectSyncResult.Synced
        } catch (exception: SecurityException) {
            // Permissions can be revoked after the check above.
            FoodLogHealthConnectSyncResult.PermissionRequired
        } catch (exception: Exception) {
            FoodLogHealthConnectSyncResult.Failed(exception.safeMessage())
        }
    }

    /** Removes only the record previously written for this exact local entry. */
    suspend fun deleteEntry(
        entry: FoodEntry,
        userOptedIn: Boolean,
    ): FoodLogHealthConnectSyncResult {
        if (!userOptedIn) return FoodLogHealthConnectSyncResult.NotOptedIn
        when (val status = availability()) {
            FoodLogHealthConnectAvailability.Available -> Unit
            else -> return FoodLogHealthConnectSyncResult.Unavailable(status)
        }
        val client = try {
            clientFactory(appContext)
        } catch (exception: Exception) {
            return FoodLogHealthConnectSyncResult.Failed(exception.safeMessage())
        }
        val hasPermission = try {
            client.permissionController.getGrantedPermissions().contains(WRITE_NUTRITION_PERMISSION)
        } catch (exception: Exception) {
            return FoodLogHealthConnectSyncResult.Failed(exception.safeMessage())
        }
        if (!hasPermission) return FoodLogHealthConnectSyncResult.PermissionRequired
        return try {
            client.deleteRecords(
                NutritionRecord::class,
                emptyList(),
                listOf(entry.healthConnectClientRecordId()),
            )
            FoodLogHealthConnectSyncResult.Synced
        } catch (exception: SecurityException) {
            FoodLogHealthConnectSyncResult.PermissionRequired
        } catch (exception: Exception) {
            FoodLogHealthConnectSyncResult.Failed(exception.safeMessage())
        }
    }

    companion object {
        val WRITE_NUTRITION_PERMISSION: String =
            HealthPermission.getWritePermission(NutritionRecord::class)
    }
}

internal sealed interface FoodLogHealthConnectAvailability {
    data object Available : FoodLogHealthConnectAvailability
    data object ProviderUpdateRequired : FoodLogHealthConnectAvailability
    data object Unavailable : FoodLogHealthConnectAvailability
}

internal sealed interface FoodLogHealthConnectSyncResult {
    data object Synced : FoodLogHealthConnectSyncResult
    data object NotOptedIn : FoodLogHealthConnectSyncResult
    data object PermissionRequired : FoodLogHealthConnectSyncResult
    data class Unavailable(val availability: FoodLogHealthConnectAvailability) :
        FoodLogHealthConnectSyncResult
    data class Failed(val reason: String) : FoodLogHealthConnectSyncResult
}

internal fun FoodEntry.healthConnectClientRecordId(): String =
    "foodlog-entry-${uuid.ifBlank { id.toString() }}"

internal fun FoodEntry.healthConnectClientRecordVersion(syncVersionMillis: Long): Long =
    maxOf(consumedAtMillis, syncVersionMillis).coerceAtLeast(1L)

internal fun FoodEntry.toNutritionRecord(
    zoneId: ZoneId,
    syncVersionMillis: Long = System.currentTimeMillis(),
): NutritionRecord {
    val consumedAt = Instant.ofEpochMilli(consumedAtMillis)
    val endTime = consumedAt.plusMillis(1)
    val zoneOffset = zoneId.rules.getOffset(consumedAt)
    val nutrients = product.nutrients
    return NutritionRecord(
        startTime = consumedAt,
        startZoneOffset = zoneOffset,
        endTime = endTime,
        endZoneOffset = zoneId.rules.getOffset(endTime),
        metadata = Metadata.manualEntry(
            clientRecordId = healthConnectClientRecordId(),
            clientRecordVersion = healthConnectClientRecordVersion(syncVersionMillis),
        ),
        energy = nutrients.caloriesKcal.scaledEnergy(entry = this),
        protein = nutrients.proteinGrams.scaledMass(entry = this),
        totalCarbohydrate = nutrients.carbohydrateGrams.scaledMass(entry = this),
        totalFat = nutrients.fatGrams.scaledMass(entry = this),
        sugar = nutrients.sugarsGrams.scaledMass(entry = this),
        dietaryFiber = nutrients.fiberGrams.scaledMass(entry = this),
        saturatedFat = nutrients.saturatedFatGrams.scaledMass(entry = this),
        sodium = nutrients.sodiumMilligrams.scaledMilligrams(entry = this)
            // Open Food Facts' salt value is sodium chloride. Health Connect expects sodium.
            ?: nutrients.saltGrams?.div(SALT_GRAMS_PER_SODIUM_GRAM)?.scaledMass(entry = this),
        cholesterol = nutrients.cholesterolMilligrams.scaledMilligrams(entry = this),
        potassium = nutrients.potassiumMilligrams.scaledMilligrams(entry = this),
        calcium = nutrients.calciumMilligrams.scaledMilligrams(entry = this),
        iron = nutrients.ironMilligrams.scaledMilligrams(entry = this),
        caffeine = nutrients.caffeineMilligrams.scaledMilligrams(entry = this),
        name = product.name,
        mealType = mealType.toHealthConnectMealType(consumedAt, zoneId),
    )
}

internal fun MealType.toHealthConnectMealType(consumedAt: Instant, zoneId: ZoneId): Int = when (this) {
    MealType.BREAKFAST -> HealthMealType.MEAL_TYPE_BREAKFAST
    MealType.LUNCH -> HealthMealType.MEAL_TYPE_LUNCH
    MealType.DINNER -> HealthMealType.MEAL_TYPE_DINNER
    MealType.SNACK -> HealthMealType.MEAL_TYPE_SNACK
    MealType.UNKNOWN -> when (inferredMealType(consumedAt.toEpochMilli(), zoneId)) {
        MealType.BREAKFAST -> HealthMealType.MEAL_TYPE_BREAKFAST
        MealType.LUNCH -> HealthMealType.MEAL_TYPE_LUNCH
        MealType.DINNER -> HealthMealType.MEAL_TYPE_DINNER
        MealType.SNACK -> HealthMealType.MEAL_TYPE_SNACK
        MealType.UNKNOWN -> HealthMealType.MEAL_TYPE_UNKNOWN
    }
}

private fun Double?.scaledMass(entry: FoodEntry): Mass? =
    scaledValue(this, entry.quantityGrams)?.let(Mass::grams)

private fun Double?.scaledMilligrams(entry: FoodEntry): Mass? =
    scaledValue(this, entry.quantityGrams)?.let(Mass::milligrams)

private fun Double?.scaledEnergy(entry: FoodEntry): Energy? =
    scaledValue(this, entry.quantityGrams)?.let(Energy::kilocalories)

private fun Exception.safeMessage(): String = message ?: javaClass.simpleName

private const val SALT_GRAMS_PER_SODIUM_GRAM = 2.5
private val healthConnectVersionClock = AtomicLong(System.currentTimeMillis() * 1_000L)
private fun nextHealthConnectVersion(): Long = healthConnectVersionClock.updateAndGet { previous ->
    maxOf(previous + 1L, System.currentTimeMillis() * 1_000L)
}

internal const val FOOD_LOG_PREFERENCES = "food_log_settings"
internal const val FOOD_LOG_HEALTH_SYNC_KEY = "health_connect_write_enabled"
