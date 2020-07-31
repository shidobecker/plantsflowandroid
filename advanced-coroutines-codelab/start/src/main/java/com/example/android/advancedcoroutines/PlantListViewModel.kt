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

import androidx.lifecycle.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * A flow is an asynchronous version of a Sequence, a type of collection whose values are lazily produced. Just like a sequence, a
 * flow produces each value on-demand whenever the value is needed, and flows can contain an infinite number of values.

So, why did Kotlin introduce a new Flow type, and how is it different than a regular sequence? The answer lies in the magic of async. Flow includes
full support for coroutines. That means you can build, transform, and consume aFlow using coroutines.
You can also control concurrency, which means coordinating the execution of several coroutines declaratively with Flow.
 */

/**
 * The [ViewModel] for fetching a list of [Plant]s.
 */
class PlantListViewModel internal constructor(
    private val plantRepository: PlantRepository
) : ViewModel() {


    /**
     * This defines a new ConflatedBroadcastChannel. This is a special kind of coroutine-based value holder that holds only the last value
     * it was given. It's a thread-safe concurrency primitive, so you can write to it from multiple threads at the same time
     * (and whichever is considered "last" will win).

    You can also subscribe to get updates to the current value. Overall, it has the similar behavior to a LiveData–it just holds the last
    value and lets you observe changes to it. However, unlike LiveData, you have to use coroutines to read values on multiple threads.

    A ConflatedBroadcastChannel is often a good way to insert events into a flow. It provides a concurrency primitive (or low-level tool)
    for passing values between several coroutines.

    By conflating the events, we keep track of only the most recent event. This is often the correct thing to do, since UI events may come in
    faster than processing, and we usually don't care about intermediate values.

    If you do need to pass all events between coroutines and don't want conflation, consider using a Channel which offers the semantics of a
    BlockingQueue using suspend functions. The channelFlow builder can be used to make channel backed flows.
     */
    private val growZoneChannel = ConflatedBroadcastChannel<GrowZone>()


    /**
     * Since flow offers main-safety and the ability to cancel, you can choose to pass the
     * Flow all the way through to the UI layer without converting it to a LiveData.
     * However, for this codelab we will stick to using LiveData in the UI layer.

    Also in the ViewModel, add a cache update to the init block. This step is optional for now,
    but if you clear your cache and don't add this call, you will not see any data in the app.
     */
    val plantsUsingFlow: LiveData<List<Plant>> =
        growZoneChannel.asFlow()
            .flatMapLatest { growZone -> //Flow's flatMapLatest extensions allow you to switch between multiple flows.
                if (growZone == NoGrowZone) {
                    plantRepository.plantsFlow
                } else {
                    plantRepository.getPlantsWithGrowZoneFlow(growZone)
                }
            }.asLiveData()


    /**
     * Request a snackbar to display a string.
     *
     * This variable is private because we don't want to expose [MutableLiveData].
     *
     * MutableLiveData allows anyone to set a value, and [PlantListViewModel] is the only
     * class that should be setting values.
     */
    private val _snackbar = MutableLiveData<String?>()

    /**
     * Request a snackbar to display a string.
     */
    val snackbar: LiveData<String?>
        get() = _snackbar

    private val _spinner = MutableLiveData<Boolean>(false)

    /**
     * Show a loading spinner if true
     */
    val spinner: LiveData<Boolean>
        get() = _spinner

    /**
     * The current growZone selection.
     */
    private val growZone = MutableLiveData<GrowZone>(NoGrowZone)

    /**
     * A list of plants that updates based on the current filter.
     */
    val plants: LiveData<List<Plant>> = growZone.switchMap { growZone ->
        if (growZone == NoGrowZone) {
            plantRepository.plants
        } else {
            plantRepository.getPlantsWithGrowZone(growZone)
        }
    }

    init {
        // When creating a new ViewModel, clear the grow zone and perform any related udpates
        clearGrowZoneNumber()  // keep this

        // fetch the full plant list
        // launchDataLoad { plantRepository.tryUpdateRecentPlantsCache() }


        //This code will launch a new coroutine to observe the values sent to growZoneChannel.
        // You can comment out the network calls in the methods below now as they're only needed for the LiveData version

        loadDataFor(growZoneChannel) { growZone ->
            _spinner.value = true
            if (growZone == NoGrowZone) {
                plantRepository.tryUpdateRecentPlantsCache()
            } else {
                plantRepository.tryUpdateRecentPlantsForGrowZoneCache(growZone)
            }
        }
        /*  growZoneChannel.asFlow()
              .mapLatest { growZone ->
                  _spinner.value = true
                  if (growZone == NoGrowZone) {
                      plantRepository.tryUpdateRecentPlantsCache()
                  } else {
                      plantRepository.tryUpdateRecentPlantsForGrowZoneCache(growZone)
                  }
              }
              .onCompletion { _spinner.value = false }
              .catch { throwable -> _snackbar.value = throwable.message }
              .launchIn(viewModelScope)*/
    }

    fun <T> loadDataFor(source: ConflatedBroadcastChannel<T>, block: suspend (T) -> Unit) {
        source.asFlow().mapLatest { block(source.value) }.onCompletion { _spinner.value = false }
            .catch { throwable -> _snackbar.value = throwable.message }
            .launchIn(viewModelScope)
    }

    /**
     * Filter the list to this grow zone.
     *
     * In the starter code version, this will also start a network request. After refactoring,
     * updating the grow zone will automatically kickoff a network request.
     */
    fun setGrowZoneNumber(num: Int) {
        growZone.value = GrowZone(num)
        growZoneChannel.offer(GrowZone(num))

        /* launchDataLoad {
            plantRepository.tryUpdateRecentPlantsForGrowZoneCache(GrowZone(num))
        }*/
    }

    /**
     * Clear the current filter of this plants list.
     *
     * In the starter code version, this will also start a network request. After refactoring,
     * updating the grow zone will automatically kickoff a network request.
     *
     *
     * To let the channel know about the filter change, we can call offer.
     * This is a regular (non-suspending) function, and it's an easy way to communicate an event into a coroutine like we're doing here.
     */
    fun clearGrowZoneNumber() {
        growZone.value = NoGrowZone
        growZoneChannel.offer(NoGrowZone)

        launchDataLoad {
            plantRepository.tryUpdateRecentPlantsCache()
        }
    }

    /**
     * Return true iff the current list is filtered.
     */
    fun isFiltered() = growZone.value != NoGrowZone

    /**
     * Called immediately after the UI shows the snackbar.
     */
    fun onSnackbarShown() {
        _snackbar.value = null
    }

    /**
     * Helper function to call a data load function with a loading spinner; errors will trigger a
     * snackbar.
     *
     * By marking [block] as [suspend] this creates a suspend lambda which can call suspend
     * functions.
     *
     * @param block lambda to actually load data. It is called in the viewModelScope. Before calling
     *              the lambda, the loading spinner will display. After completion or error, the
     *              loading spinner will stop.
     */
    private fun launchDataLoad(block: suspend () -> Unit): Job {
        return viewModelScope.launch {
            try {
                _spinner.value = true
                block()
            } catch (error: Throwable) {
                _snackbar.value = error.message
            } finally {
                _spinner.value = false
            }
        }
    }
}
