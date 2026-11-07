package org.example.project.core.presentation.components.draggableList

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> DraggableLazyList(
    items: List<T>,
    onSwap: (Int, Int) -> Unit,
    content: @Composable (item: T?, isDragging: Boolean, dragState: DragDropState, index: Int) -> Unit,
) {
    var overscrollJob by remember { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val dragDropState =
        rememberDragDropState(listState) { fromIndex, toIndex ->
            onSwap(fromIndex, toIndex)
        }
    LazyColumn(
        modifier =
            Modifier
                .wrapContentHeight()
                .padding(top = 4.dp)
                .pointerInput(dragDropState) {
                    detectDragGesturesAfterLongPress(
                        onDrag = { change, offset ->
                            change.consume()
                            dragDropState.onDrag(offset = offset)

                            if (overscrollJob?.isActive == true) {
                                return@detectDragGesturesAfterLongPress
                            }

                            dragDropState
                                .checkForOverScroll()
                                .takeIf { it != 0f }
                                ?.let {
                                    overscrollJob =
                                        scope.launch {
                                            dragDropState.state.animateScrollBy(
                                                it * 1.3f,
                                                tween(easing = FastOutLinearInEasing),
                                            )
                                        }
                                }
                                ?: run { overscrollJob?.cancel() }
                        },
                        onDragStart = { offset -> dragDropState.onDragStart(offset) },
                        onDragEnd = {
                            dragDropState.onDragInterrupted()
                            overscrollJob?.cancel()
                        },
                        onDragCancel = {
                            dragDropState.onDragInterrupted()
                            overscrollJob?.cancel()
                        },
                    )
                },
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        items(
            count = items.size,
        ) { index ->
            DraggableItem(
                dragDropState = dragDropState,
                index = index,
                modifier = Modifier,
            ) { isDragging ->
                content(items.getOrNull(index), isDragging, dragDropState, index)
            }
        }
    }
}
