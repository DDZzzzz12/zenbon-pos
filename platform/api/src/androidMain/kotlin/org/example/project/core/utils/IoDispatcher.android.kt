package org.example.project.core.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun platformIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
