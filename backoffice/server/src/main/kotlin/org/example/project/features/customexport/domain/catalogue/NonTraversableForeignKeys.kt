package org.example.project.features.customexport.domain.catalogue

/**
 * Foreign keys the schema walk must **not** follow, in either direction.
 *
 * ## The problem this solves, and the one it deliberately does not
 *
 * [SchemaGraph] derives relations from the foreign-key graph, and that is the right source: the keys
 * are already true of the database, so nothing can drift. It has one blind spot. A **mutual 1:1** —
 * one link modelled as a foreign key on *both* sides — looks to the walker like two relations,
 * because structurally that is exactly what it is. It follows both, reaches the same table twice, and
 * offers the same columns under two headings. `employees` and `employee_info` are that case: the
 * picker's Personal subject showed "Personaldaten" and "Personaldaten (alternative Verknüpfung)",
 * four identical columns each, with no difference an operator could act on.
 *
 * The fix is **not** "drop a path when another path already reaches that table". Two foreign keys
 * into one table are usually two genuinely different relations, and this schema proves it:
 * `kassenbuch_header` holds both `cash_book_id` and `counterparty_book_id`, the entry's own book and
 * the Gegenkasse of a Geldtransit. They reach one table and they mean different things; both must
 * keep being offered, and `RelationGroupLabelTest` pins that they are.
 *
 * Structure cannot tell the two situations apart — the difference is what the keys *mean* — so the
 * ban has to be **declared**. That is what this file is: the short list of foreign keys where a human
 * has decided the second route says nothing the first does not.
 *
 * ## Keyed by the same segment vocabulary as everything else
 *
 * An entry is one `SchemaGraph.segmentOf` segment, `<target>:<direction>:<column>` — the same string
 * a stored field key is built from and the same one [ExportRelationLabels] is keyed by. Keying by the
 * column alone would not do: a column traversed forwards and backwards is two different edges, and a
 * ban has to be able to name one without the other. Because one foreign key yields two segments, a
 * ban on the whole key lists **both**, which is why the entries come in pairs below.
 *
 * ## Which side of a mutual 1:1 to keep
 *
 * The reliable one — the side the application actually maintains and reads. Getting this backwards
 * would leave the picker offering the route that is sometimes blank.
 *
 * ## When to add an entry
 *
 * When a new mutual 1:1 appears and the picker grows a duplicate group. Add the pair here rather than
 * naming the second route in [ExportRelationLabels]: a heading can only *describe* the duplicate, and
 * an operator has no use for a second block of the same four values.
 */
object NonTraversableForeignKeys {
    /**
     * Every segment the walk refuses, both directions of each banned foreign key.
     *
     * ### `employees.info_id`
     *
     * `employees.info_id` and `employee_info.employee_id` are one link written twice, so the walk
     * found the personnel record twice and offered its four columns twice. The two are **not**
     * equally trustworthy, and the schema says which is which in its own KDoc: `employee_info.employee_id`
     * is "the authoritative link", NOT NULL and unique since V90, while `employees.info_id` is
     * "back-reference to the personnel record", nullable with `ON DELETE SET NULL`.
     *
     * The application agrees. `EmployeeInfoRepositoryImpl` creates a record by setting
     * `EmployeeInfo.employee` — that is `employee_id` — and every lookup it performs
     * (`getEmployeeInfoByEmployeeId`, `updateEmployeeInfoByEmployeeId`, `deleteEmployeeInfoByEmployeeId`,
     * `employeeInfoExists`) filters on `employee_id`. `info_id` is written only as an afterthought by
     * `EmployeeInfoServiceImpl` after the row exists, and is cleared again on delete; nothing reads
     * it to find a personnel record. A row whose `info_id` never got written, or got set to null by
     * the cascade, still has its `employee_id` — so the forward route yields blank name cells where
     * the backward route finds the person. Two routes that silently disagree, one of which is right.
     *
     * So the forward route goes and `employee_info:by:employee_id` stays. Both directions of the
     * back-reference are listed: the walk must not follow it from `employees`, and must not follow it
     * back from `employee_info` either — it is the same untrustworthy column seen from the other end.
     */
    val SEGMENTS: Set<String> =
        setOf(
            "employee_info:via:info_id",
            "employees:by:info_id",
        )

    /** Whether the walk may not follow the edge [segment] names. */
    fun bans(segment: String): Boolean = segment in SEGMENTS
}
