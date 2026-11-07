package org.example.project.utils

/**
 * Marks a public member that exists for tests in another module to reach.
 *
 * These members were `internal` until the platform extraction put the tests and the code in different
 * modules. The contract: the member exists for tests; outside its declaring file only test code may
 * call it. The declaring file itself may still use it in production (a class calling its own hook —
 * e.g. `SchedulerEngine.start`, `TlsRootCa.current`, `ApiDocsPolicy.serverOverride`,
 * `BillServiceImpl.createBillInternal`), behind an `@OptIn(TestOnlyApi::class)` on the using function
 * (or property), never file- or module-wide. The opt-in is `ERROR` level: test compilations opt in
 * globally in their build file, main compilations never do; `TestOnlyApiGuardTest` fails a production
 * opt-in in any other file.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message =
        "Exists for tests; only the declaring file may use it in production, behind a function-level " +
            "@OptIn(TestOnlyApi::class).",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
)
annotation class TestOnlyApi
