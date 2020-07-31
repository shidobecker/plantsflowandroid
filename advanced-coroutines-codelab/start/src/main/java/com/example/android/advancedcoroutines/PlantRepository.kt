/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.advancedcoroutines

import androidx.annotation.AnyThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import com.example.android.advancedcoroutines.util.CacheOnSuccess
import com.example.android.advancedcoroutines.utils.ComparablePair
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/**
 *
 * https://codelabs.developers.google.com/codelabs/advanced-kotlin-coroutines/#9
 * Repository module for handling data operations.
 *
 * This PlantRepository exposes two UI-observable database queries [plants] and
 * [getPlantsWithGrowZone].
 *
 * To update the plants cache, call [tryUpdateRecentPlantsForGrowZoneCache] or
 * [tryUpdateRecentPlantsCache].
 */
class PlantRepository private constructor(
    private val plantDao: PlantDao,
    private val plantService: NetworkService,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    /**
     * Fetch a list of [Plant]s from the database.
     * Returns a LiveData-wrapped List of Plants.
     */
    val plants: LiveData<List<Plant>> = liveData<List<Plant>> {
        val plantsLiveData = plantDao.getPlants()
        val customSortOrder = plantsListSortOrderCache.getOrAwait()
        emitSource(plantsLiveData.map { plantList ->
            plantList.applySort(customSortOrder)
        })
    }

    /**
     * Fetch a list of [Plant]s from the database that matches a given [GrowZone].
     * Returns a LiveData-wrapped List of Plants.
     */
    fun getPlantsWithGrowZone(growZone: GrowZone) = liveData<List<Plant>> {
        plantDao.getPlantsWithGrowZoneNumber(growZone.number)
            .switchMap { plantList ->
                liveData {
                    val customSortOrder = plantsListSortOrderCache.getOrAwait()
                    emit(plantList.applyMainSafeSort(customSortOrder))
                }
            }

    }


    /**
     * This defines a Flow that, when collected, will call getOrAwait and emit the sort order.

    Since this flow only emits a single value, you can also build it directly from the getOrAwait function using asFlow.


    The transform onStart will happen when an observer listens before other operators,
    and it can emit placeholder values. So here we're emitting an empty list, delaying calling getOrAwait by 1500ms,
    then continuing the original flow. If you run the app now, you'll see that the Room database query returns right away,
    combining with the empty list (which means it'll sort alphabetically). Then around 1500ms later, it applies the custom sort.

    private val customSortFlow = flow { emit(plantsListSortOrderCache.getOrAwait()) }
     */
    private val customSortFlow = flow { emit(plantsListSortOrderCache.getOrAwait()) }.onStart {
        emit(listOf())
        delay(1500)
    }


    /**
     * This code creates a new Flow that calls getOrAwait and emits the result as its first and only value.
     * It does this by referencing the getOrAwait method using :: and calling asFlow on the resulting Function object.

    Both of these flows do the same thing, call getOrAwait and emit the result before completing.
     */
    // private val customSortFlow2 = plantsListSortOrderCache::getOrAwait.asFlow()


    /**
     * The combine operator combines two flows together. Both flows will run in their own coroutine,
     * then whenever either flow produces a new value the transformation will be called with the latest value from either flow.

    By using combine, we can combine the cached network lookup with our database query.
    Both of them will run on different coroutines concurrently. That means that while Room starts the network request,
    Retrofit can start the network query. Then, as soon as a result is available for both flows,
    it will call the combine lambda where we apply the loaded sort order to the loaded plants.


    Calling flowOn has two important effects on how the code executes:

    Launch a new coroutine on the defaultDispatcher (in this case, Dispatchers.Default) to run and collect the flow before the call to flowOn.
    Introduces a buffer to send results from the new coroutine to later calls.
    Emit the values from that buffer into the Flow after flowOn. In this case, that's asLiveData in the ViewModel.
    This is very similar to how withContext works to switch dispatchers, but it does introduce a buffer in the middle of
    our transforms that changes how the flow works. The coroutine launched by flowOn is allowed to produce results faster than
    the caller consumes them, and it will buffer a large number of them by default.

    In this case, we plan on sending the results to the UI, so we would only ever care about the most recent result.
    That's what the conflate operator does–it modifies the buffer of flowOn to store only the last result. If another result comes in
    before the previous one is read, it gets overwritten.
     */
    val plantsFlow: Flow<List<Plant>>
        get() = plantDao.getPlantsFlow()
            // When the result of customSortFlow is available,
            // this will combine it with the latest value from
            // the flow above.  Thus, as long as both `plants`
            // and `sortOrder` are have an initial value (their
            // flow has emitted at least one value), any change
            // to either `plants` or `sortOrder`  will call
            // `plants.applySort(sortOrder)`.
            .combine(customSortFlow) { plants, sortOrder -> plants.applySort(sortOrder) }
            .flowOn(defaultDispatcher).conflate()

    fun getPlantsWithGrowZoneFlow(growZoneNumber: GrowZone): Flow<List<Plant>> {
        return plantDao.getPlantsWithGrowZoneNumberFlow(growZoneNumber.number).map { plantList ->
            val sortOrderFromNetwork = plantsListSortOrderCache.getOrAwait()
            val nextValue = plantList.applyMainSafeSort(sortOrderFromNetwork)
            nextValue
        }
    }

    /**
     * By relying on regular suspend functions to handle the async work, this map operation is main-safe
     * even though it combines two async operations.

    As each result from the database is returned, we'll get the cached sort order–and if it's not ready yet,
    it will wait on the async network request. Then once we have the sort order, it's safe to call applyMainSafeSort,
    which will run the sort on the default dispatcher.

    This code is now entirely main-safe by deferring the main safety concerns to regular suspend functions.
    It's quite a bit simpler than the same transformation implemented in plantsFlow.

    In Flow, map and other operators provide a suspending lambda.

    By using the suspend and resume mechanism of coroutines, you can often orchestrate sequential async calls easily without
    using declarative transforms.

    It is an error to emit a value from a different coroutine than the one that called the suspending transformation.

    If you do launch another coroutine inside a flow operation like we're doing here inside getOrAwait and applyMainSafeSort,
    make the value is returned to the original coroutine before emitting it.

    However, it is worth noting that it will execute a bit differently. The cached value will be fetched every single time
    the database emits a new value. This is OK because we're caching it correctly in plantsListSortOrderCache,
    but if that started a new network request this implementation would make a lot of unnecessary network requests.
    In addition, in the .combine version, the network request and the database query run concurrently, while in this version
    they run in sequence.

    Due to these differences, there is not a clear rule to structure this code. In many cases, it's fine to use suspending
    transformations like we're doing here, which makes all async operations sequential. However, in other cases,
    it's better to use operators to control concurrency and provide main-safety.
     */

    @AnyThread
    suspend fun List<Plant>.applyMainSafeSort(customSortOrder: List<String>) =
        withContext(defaultDispatcher) {
            this@applyMainSafeSort.applySort(customSortOrder)
        }

    /**
     * Returns true if we should make a network request.
     */
    private fun shouldUpdatePlantsCache(): Boolean {
        // suspending function, so you can e.g. check the status of the database here
        return true
    }

    /**
     * Update the plants cache.
     *
     * This function may decide to avoid making a network requests on every call based on a
     * cache-invalidation policy.
     */
    suspend fun tryUpdateRecentPlantsCache() {
        if (shouldUpdatePlantsCache()) fetchRecentPlants()
    }

    /**
     * Update the plants cache for a specific grow zone.
     *
     * This function may decide to avoid making a network requests on every call based on a
     * cache-invalidation policy.
     */
    suspend fun tryUpdateRecentPlantsForGrowZoneCache(growZoneNumber: GrowZone) {
        if (shouldUpdatePlantsCache()) fetchPlantsForGrowZone(growZoneNumber)
    }

    /**
     * Fetch a new list of plants from the network, and append them to [plantDao]
     */
    private suspend fun fetchRecentPlants() {
        val plants = plantService.allPlants()
        plantDao.insertAll(plants)
    }

    /**
     * Fetch a list of plants for a grow zone from the network, and append them to [plantDao]
     */
    private suspend fun fetchPlantsForGrowZone(growZone: GrowZone) {
        val plants = plantService.plantsByGrowZone(growZone)
        plantDao.insertAll(plants)
    }


    //NEW CODE ---------------------------

    /**
     * plantsListSortOrderCache is used as the in-memory cache for the custom sort order.
     * It will fallback to an empty list if there's a network error, so that our app can still display data even if the
     * sorting order isn't fetched.

    This code uses the CacheOnSuccess utility class provided in the sunflower module to handle caching.
    By abstracting away the details of implementing caching like this, the application code can be more straightforward.
    Since CacheOnSuccess is already well tested, we don't need to write as many tests for our repository to ensure the correct behavior.
    It's a good idea to introduce similar higher-level abstractions in your code when using kotlinx-coroutines.
     */
    private var plantsListSortOrderCache = CacheOnSuccess(onErrorFallback = { listOf<String>() }) {
        plantService.customPlantSortOrder()
    }


    /**
     * To switch between any dispatcher, coroutines uses withContext.
     * Calling withContext switches to the other dispatcher just for the lambda then comes back to the dispatcher that called
     * it with the result of that lambda.

    By default, Kotlin coroutines provides three Dispatchers: Main, IO, and Default. The IO dispatcher is optimized for IO work
    like reading from the network or disk, while the Default dispatcher is optimized for CPU intensive tasks.
     */
    private fun List<Plant>.applySort(customSortOrder: List<String>): List<Plant> {
        return sortedBy { plant ->
            val positionForItem = customSortOrder.indexOf(plant.plantId).let { order ->
                if (order > -1) order else Int.MAX_VALUE
            }
            ComparablePair(positionForItem, plant.name)
        }
    }


    companion object {

        // For Singleton instantiation
        @Volatile
        private var instance: PlantRepository? = null

        fun getInstance(plantDao: PlantDao, plantService: NetworkService) =
            instance ?: synchronized(this) {
                instance ?: PlantRepository(plantDao, plantService).also { instance = it }
            }
    }
}
