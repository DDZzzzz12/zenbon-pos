-- The schema of a v1.0.10 installation (Flyway version 76), frozen. NOT a Flyway migration: it lives
-- outside db/migration on purpose and is never applied to the live database.
--
-- Used only by the backup restore. A pre-1.1.0 archive is loaded into a throwaway staging database
-- built from this file, then the real V77..Vmax chain runs there, exactly as an in-place upgrade
-- would; the migrated rows are what reach the live database (RestoreStaging). The migrations alone
-- cannot build this shape: they start at V33 and assume the tables Exposed created at install time.
--
-- Provenance: `pg_dump --schema-only --no-owner --no-privileges --no-comments --schema=public
-- --exclude-table=flyway_schema_history` of a fresh, empty v1.0.10 install (tag v1.0.10, 490ef1108,
-- embedded PostgreSQL 16.6), with the dump's session SETs, psql meta-commands, `CREATE SCHEMA public`
-- and comment lines removed. No data. Never edit it by hand; a later base gets its own file.

CREATE TABLE public.bill (
    id bigint NOT NULL,
    employee_id uuid NOT NULL,
    tip_recipient character varying(50) DEFAULT 'EMPLOYEE'::character varying NOT NULL,
    table_session bigint NOT NULL,
    bill_number character varying(100) NOT NULL,
    bill_amount numeric(19,4) NOT NULL,
    discount_amount numeric(19,4) DEFAULT 0 NOT NULL,
    promotion_amount numeric(19,4) DEFAULT 0 NOT NULL,
    paid_amount numeric(19,4) NOT NULL,
    tips numeric(19,4) DEFAULT 0 NOT NULL,
    start_time timestamp without time zone NOT NULL,
    end_time timestamp without time zone NOT NULL,
    deleting_reason character varying(10000),
    tse_transaction_id bigint,
    deleted_bill_id bigint,
    zbon_id bigint,
    tse_signing_status character varying(20) DEFAULT 'SIGNED'::character varying NOT NULL,
    is_training boolean DEFAULT false NOT NULL,
    terminal_id bigint
);

CREATE SEQUENCE public.bill_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.bill_id_seq OWNED BY public.bill.id;

CREATE TABLE public.bill_promotion (
    id bigint NOT NULL,
    bill_id bigint NOT NULL,
    promotion_id bigint NOT NULL,
    order_item_id bigint,
    deducted_amount numeric(19,4) NOT NULL,
    applied_by uuid NOT NULL,
    applied_at timestamp without time zone NOT NULL
);

CREATE SEQUENCE public.bill_promotion_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.bill_promotion_id_seq OWNED BY public.bill_promotion.id;

CREATE TABLE public.bill_vat_detail (
    id bigint NOT NULL,
    bill_id bigint NOT NULL,
    vat_id character varying(10) NOT NULL,
    vat_amount numeric(19,4) NOT NULL,
    net_amount numeric(19,4) NOT NULL,
    total_amount numeric(19,4) NOT NULL
);

CREATE SEQUENCE public.bill_vat_detail_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.bill_vat_detail_id_seq OWNED BY public.bill_vat_detail.id;

CREATE TABLE public.cancellation (
    id bigint NOT NULL,
    order_item_id bigint NOT NULL,
    reason character varying(500),
    user_mapping_id uuid NOT NULL,
    created_at timestamp without time zone NOT NULL,
    zbon_id bigint,
    tse_transaction_id bigint,
    tse_signing_status character varying(20) DEFAULT 'SIGNED'::character varying NOT NULL,
    is_training boolean DEFAULT false NOT NULL
);

CREATE SEQUENCE public.cancellation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.cancellation_id_seq OWNED BY public.cancellation.id;

CREATE TABLE public.card_settlement (
    id bigint NOT NULL,
    card_terminal_id bigint NOT NULL,
    zbon_id bigint,
    settlement_time timestamp without time zone NOT NULL,
    success boolean NOT NULL,
    result_code integer DEFAULT 0 NOT NULL,
    receipt_text text,
    total_amount_cents bigint DEFAULT 0 NOT NULL,
    total_transaction_count integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone NOT NULL
);

CREATE TABLE public.card_settlement_detail (
    id bigint NOT NULL,
    card_settlement_id bigint NOT NULL,
    card_type integer NOT NULL,
    card_type_name character varying(100) NOT NULL,
    transaction_count integer DEFAULT 0 NOT NULL,
    total_amount_cents bigint DEFAULT 0 NOT NULL
);

CREATE SEQUENCE public.card_settlement_detail_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.card_settlement_detail_id_seq OWNED BY public.card_settlement_detail.id;

CREATE SEQUENCE public.card_settlement_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.card_settlement_id_seq OWNED BY public.card_settlement.id;

CREATE TABLE public.card_terminal (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    host character varying(255) NOT NULL,
    port integer DEFAULT 20007 NOT NULL,
    password character varying(20) DEFAULT '000000'::character varying NOT NULL,
    terminal_id character varying(50),
    software_version character varying(50),
    is_active boolean DEFAULT true NOT NULL,
    is_connected boolean DEFAULT false NOT NULL,
    is_busy boolean DEFAULT false NOT NULL,
    shop_info_id bigint NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL
);

CREATE SEQUENCE public.card_terminal_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.card_terminal_id_seq OWNED BY public.card_terminal.id;

CREATE TABLE public.card_transaction (
    id bigint NOT NULL,
    payment_id bigint,
    card_terminal_id bigint NOT NULL,
    trace_number character varying(50) NOT NULL,
    receipt_number character varying(50),
    amount_cents bigint NOT NULL,
    currency character varying(3) DEFAULT 'EUR'::character varying NOT NULL,
    card_type integer,
    card_type_name character varying(100),
    pan_last4 character varying(4),
    expiry_date character varying(4),
    aid character varying(50),
    payment_type character varying(20) NOT NULL,
    result_code integer DEFAULT 0 NOT NULL,
    receipt_text_merchant text,
    receipt_text_customer text,
    transaction_time timestamp without time zone NOT NULL,
    created_at timestamp without time zone NOT NULL
);

CREATE SEQUENCE public.card_transaction_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.card_transaction_id_seq OWNED BY public.card_transaction.id;

CREATE TABLE public.combinable_tables (
    table_a_id bigint NOT NULL,
    table_b_id bigint NOT NULL
);

CREATE TABLE public.cost_center (
    id uuid NOT NULL,
    code character varying(20) NOT NULL,
    name character varying(100) NOT NULL,
    is_active boolean DEFAULT true NOT NULL
);

CREATE TABLE public.customer_addresses (
    address_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    address_type character varying(20) DEFAULT 'delivery'::character varying NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    street character varying(255) NOT NULL,
    house_number character varying(10) NOT NULL,
    apartment character varying(50),
    postal_code character varying(10) NOT NULL,
    city character varying(100) NOT NULL,
    state character varying(100),
    country character varying(100) DEFAULT 'Germany'::character varying NOT NULL,
    additional_info text,
    latitude numeric(10,8),
    longitude numeric(11,8),
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL
);

CREATE TABLE public.customers (
    customer_id uuid NOT NULL,
    customer_number character varying(20) NOT NULL,
    first_name character varying(100) NOT NULL,
    last_name character varying(100) NOT NULL,
    email character varying(255),
    phone character varying(20),
    notes text,
    marketing_consent boolean DEFAULT false NOT NULL,
    data_processing_consent boolean DEFAULT true NOT NULL,
    last_purchase_at timestamp without time zone,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT '2026-10-02 09:44:01.015'::timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL
);

CREATE TABLE public.datev_export_log (
    id bigint NOT NULL,
    export_type character varying(32) NOT NULL,
    period_start timestamp without time zone NOT NULL,
    period_end timestamp without time zone NOT NULL,
    exported_at timestamp without time zone NOT NULL,
    exported_by_user_id uuid NOT NULL,
    file_hash character varying(64) NOT NULL,
    file_name character varying(255) NOT NULL,
    row_count integer NOT NULL,
    entry_count integer NOT NULL,
    period_folder character varying(64) NOT NULL,
    source character varying(8) NOT NULL
);

CREATE SEQUENCE public.datev_export_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.datev_export_log_id_seq OWNED BY public.datev_export_log.id;

CREATE TABLE public.document_bank_statement_metadata (
    id bigint NOT NULL,
    pdf_document_id bigint NOT NULL,
    bank_name character varying(255),
    bic character varying(11),
    iban character varying(34),
    account_number character varying(50),
    statement_number character varying(50),
    statement_date timestamp without time zone,
    period_start timestamp without time zone,
    period_end timestamp without time zone,
    opening_balance numeric(15,2),
    closing_balance numeric(15,2),
    currency character varying(3) DEFAULT 'EUR'::character varying NOT NULL,
    transactions_json text
);

CREATE SEQUENCE public.document_bank_statement_metadata_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.document_bank_statement_metadata_id_seq OWNED BY public.document_bank_statement_metadata.id;

CREATE TABLE public.document_invoice_metadata (
    id bigint NOT NULL,
    pdf_document_id bigint NOT NULL,
    vendor_name character varying(255),
    invoice_number character varying(100),
    invoice_date timestamp without time zone,
    due_date timestamp without time zone,
    net_amount numeric(15,2),
    gross_amount numeric(15,2),
    tax_amount numeric(15,2),
    tax_rate numeric(5,2),
    currency character varying(3) DEFAULT 'EUR'::character varying NOT NULL,
    iban character varying(34),
    payment_reference character varying(255),
    supplier_city character varying(100),
    vat_id character varying(50),
    account_no character varying(20),
    bp_account_no character varying(20),
    delivery_date timestamp without time zone,
    booking_text character varying(60),
    tax_line_items_json text,
    line_items_json text,
    payment_details_json text,
    discount_terms_json text,
    invoice_type character varying(30),
    drawee_no character varying(100),
    order_id character varying(100),
    supplier_street character varying(255),
    supplier_zip character varying(20),
    supplier_country character varying(10),
    supplier_phone character varying(50),
    supplier_email character varying(255),
    ship_to_country character varying(10),
    ship_from_country character varying(10),
    payment_conditions_text character varying(255),
    paid_at timestamp without time zone,
    payment_dunning_block boolean DEFAULT false NOT NULL,
    cost_category_id character varying(20),
    cost_category_id2 character varying(20),
    cost_amount numeric(15,2),
    exchange_rate numeric(18,6),
    eu_tax_rate numeric(5,2),
    bu_code character varying(10),
    type_of_receivable character varying(50),
    delivery_date_start timestamp without time zone,
    delivery_date_end timestamp without time zone,
    additional_info_json text
);

CREATE SEQUENCE public.document_invoice_metadata_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.document_invoice_metadata_id_seq OWNED BY public.document_invoice_metadata.id;

CREATE TABLE public.document_links (
    id bigint NOT NULL,
    document_id bigint NOT NULL,
    bank_statement_document_id bigint NOT NULL,
    linked_by character varying(100) NOT NULL,
    linked_at timestamp without time zone NOT NULL,
    notes text
);

CREATE SEQUENCE public.document_links_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.document_links_id_seq OWNED BY public.document_links.id;

CREATE TABLE public.documents (
    id bigint NOT NULL,
    filename character varying(255) NOT NULL,
    file_type character varying(50) NOT NULL,
    file_path character varying(512) NOT NULL,
    file_size bigint NOT NULL,
    uploaded_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    uploaded_by uuid NOT NULL,
    metadata_json text,
    is_active boolean DEFAULT true NOT NULL,
    content_hash character varying(64) NOT NULL,
    ocr_text text,
    media_type character varying(50) DEFAULT 'application/pdf'::character varying NOT NULL,
    processing_status character varying(20) DEFAULT 'NONE'::character varying NOT NULL,
    processing_retry_count integer DEFAULT 0 NOT NULL,
    processing_error text,
    processing_started_at bigint
);

CREATE SEQUENCE public.documents_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.documents_id_seq OWNED BY public.documents.id;

CREATE TABLE public.employee_info (
    employee_info_id integer NOT NULL,
    employee_id uuid NOT NULL,
    first_name character varying(100) NOT NULL,
    last_name character varying(100) NOT NULL,
    email character varying(100),
    phone_number character varying(20),
    date_of_birth date,
    street character varying(200),
    city character varying(100),
    state character varying(50),
    zip_code character varying(20),
    country character varying(50) DEFAULT 'DE'::character varying,
    hire_date date,
    job_title character varying(100),
    hourly_rate numeric(10,2),
    salary numeric(12,2),
    contracted_hours numeric(5,2),
    contracted_hours_period character varying(10) DEFAULT 'WEEKLY'::character varying,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL
);

CREATE SEQUENCE public.employee_info_employee_info_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.employee_info_employee_info_id_seq OWNED BY public.employee_info.employee_info_id;

CREATE TABLE public.employee_permissions (
    employee_id uuid NOT NULL,
    permission_id integer NOT NULL
);

CREATE TABLE public.employee_zbon_payment_breakdown (
    id bigint NOT NULL,
    employee_zbon_summary_id bigint NOT NULL,
    payment_type_id bigint NOT NULL,
    currency character varying(3) NOT NULL,
    amount numeric(19,4) NOT NULL
);

CREATE SEQUENCE public.employee_zbon_payment_breakdown_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.employee_zbon_payment_breakdown_id_seq OWNED BY public.employee_zbon_payment_breakdown.id;

CREATE TABLE public.employee_zbon_summary (
    id bigint NOT NULL,
    z_bon_id bigint NOT NULL,
    employee_id uuid NOT NULL,
    tips numeric(19,4) NOT NULL,
    total_revenue numeric(19,4) NOT NULL,
    created_at timestamp without time zone NOT NULL
);

CREATE SEQUENCE public.employee_zbon_summary_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.employee_zbon_summary_id_seq OWNED BY public.employee_zbon_summary.id;

CREATE TABLE public.employees (
    employee_id uuid NOT NULL,
    username character varying(50) NOT NULL,
    password_hash character varying(255) NOT NULL,
    employee_number character varying(20) NOT NULL,
    employment_status character varying(50) DEFAULT 'ACTIVE'::character varying NOT NULL,
    info_id integer,
    role character varying(50) NOT NULL,
    pin character varying(10),
    created_at timestamp without time zone DEFAULT '2026-10-02 09:44:01.004'::timestamp without time zone NOT NULL,
    updated_at timestamp without time zone DEFAULT '2026-10-02 09:44:01.005'::timestamp without time zone NOT NULL,
    last_login timestamp without time zone,
    notes text,
    language_preference character varying(10) DEFAULT 'DE'::character varying NOT NULL,
    training_mode boolean DEFAULT false NOT NULL,
    default_cost_center_id uuid,
    personnel_number character varying(20),
    in_rotation_pool boolean DEFAULT false NOT NULL
);

CREATE TABLE public.entry_log (
    id bigint NOT NULL,
    type character varying(50) NOT NULL,
    operation character varying(50) NOT NULL,
    entity_id character varying(50) NOT NULL,
    entity_name character varying(50) NOT NULL,
    message character varying(500),
    employee_id uuid,
    created_at timestamp without time zone DEFAULT '2026-10-02 09:44:01.055'::timestamp without time zone NOT NULL
);

CREATE SEQUENCE public.entry_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.entry_log_id_seq OWNED BY public.entry_log.id;

CREATE TABLE public.favourite_product (
    id bigint NOT NULL,
    product_id bigint NOT NULL,
    sorting_position integer NOT NULL,
    order_count bigint DEFAULT 0 NOT NULL,
    total_revenue numeric(19,4) DEFAULT 0 NOT NULL,
    last_period_count bigint DEFAULT 0 NOT NULL,
    last_period_revenue numeric(19,4) DEFAULT 0 NOT NULL,
    calculated_at character varying(50),
    period_days integer DEFAULT 30 NOT NULL
);

CREATE SEQUENCE public.favourite_product_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.favourite_product_id_seq OWNED BY public.favourite_product.id;

CREATE TABLE public.item_extras (
    order_item_id bigint NOT NULL,
    product_extras_id integer NOT NULL
);

CREATE TABLE public.item_transfer (
    id bigint NOT NULL,
    order_item_id bigint,
    order_item_ids jsonb,
    employee_id uuid NOT NULL,
    from_table_id bigint NOT NULL,
    to_table_id bigint NOT NULL,
    transfer_time timestamp without time zone NOT NULL
);

CREATE SEQUENCE public.item_transfer_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.item_transfer_id_seq OWNED BY public.item_transfer.id;

CREATE TABLE public.job_executions (
    id uuid NOT NULL,
    job_id uuid NOT NULL,
    started_at timestamp without time zone NOT NULL,
    finished_at timestamp without time zone,
    status text NOT NULL,
    trigger_source text NOT NULL,
    result_message text,
    error_message text
);

CREATE TABLE public.kassenbuch_header (
    id bigint NOT NULL,
    shop_id bigint NOT NULL,
    employee_id uuid NOT NULL,
    entry_type character varying(50) NOT NULL,
    beleg_nr character varying(20) NOT NULL,
    beleg_datum character varying(10) NOT NULL,
    belegtext character varying(60) NOT NULL,
    waehrung character varying(3) DEFAULT 'EUR'::character varying NOT NULL,
    bu character varying(4),
    gegenkonto character varying(5),
    kost1 character varying(36),
    kost2 character varying(36),
    nachricht character varying(120),
    total_amount numeric(19,4) NOT NULL,
    current_balance numeric(19,4) NOT NULL,
    tse_transaction_id bigint,
    zbon_id bigint,
    tse_signing_status character varying(20) DEFAULT 'SIGNED'::character varying NOT NULL,
    linked_document_id bigint,
    is_locked boolean DEFAULT false NOT NULL,
    created_at timestamp without time zone DEFAULT '2026-10-02 09:44:01.057'::timestamp without time zone NOT NULL
);

CREATE SEQUENCE public.kassenbuch_header_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.kassenbuch_header_id_seq OWNED BY public.kassenbuch_header.id;

CREATE TABLE public.kassenbuch_line_item (
    id bigint NOT NULL,
    header_id bigint NOT NULL,
    line_number integer NOT NULL,
    vorz_betrag numeric(12,2) NOT NULL,
    ust_satz character varying(5),
    belegtext character varying(60),
    kostmenge numeric(16,4),
    skonto numeric(10,2),
    sub_balance numeric(19,4) NOT NULL
);

CREATE SEQUENCE public.kassenbuch_line_item_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.kassenbuch_line_item_id_seq OWNED BY public.kassenbuch_line_item.id;

CREATE TABLE public.llm_prompts (
    id uuid NOT NULL,
    document_type character varying(50) NOT NULL,
    name character varying(100) NOT NULL,
    system_prompt text NOT NULL,
    extraction_schema text NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL
);

CREATE TABLE public.location_types (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    english_name character varying(100) NOT NULL,
    chinese_name character varying(100) NOT NULL,
    is_virtual boolean DEFAULT false NOT NULL,
    is_takeaway boolean DEFAULT false NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL
);

CREATE SEQUENCE public.location_types_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.location_types_id_seq OWNED BY public.location_types.id;

CREATE TABLE public.opening_hours (
    id bigint NOT NULL,
    day_of_week integer NOT NULL,
    open_time time without time zone NOT NULL,
    close_time time without time zone NOT NULL,
    is_closed boolean DEFAULT false NOT NULL,
    shop_id bigint NOT NULL,
    created_at timestamp without time zone NOT NULL
);

CREATE SEQUENCE public.opening_hours_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.opening_hours_id_seq OWNED BY public.opening_hours.id;

CREATE TABLE public.opening_hours_override (
    id bigint NOT NULL,
    override_date date NOT NULL,
    open_time time without time zone,
    close_time time without time zone,
    is_closed boolean DEFAULT false NOT NULL,
    reason character varying(200),
    shop_id bigint NOT NULL,
    created_at timestamp without time zone NOT NULL
);

CREATE SEQUENCE public.opening_hours_override_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.opening_hours_override_id_seq OWNED BY public.opening_hours_override.id;

CREATE TABLE public.payment (
    id bigint NOT NULL,
    bill_id bigint NOT NULL,
    payment_type_id bigint NOT NULL,
    amount numeric(19,4) NOT NULL,
    payment_status character varying(50) NOT NULL,
    payment_time timestamp without time zone NOT NULL,
    refunded_bill_id bigint,
    currency character varying(10) NOT NULL,
    refund_time timestamp without time zone,
    transaction_id character varying(255)
);

CREATE SEQUENCE public.payment_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.payment_id_seq OWNED BY public.payment.id;

CREATE TABLE public.payment_type (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    en_name character varying(100),
    ch_name character varying(100),
    is_cash boolean DEFAULT false NOT NULL,
    is_voucher boolean DEFAULT false NOT NULL,
    is_active boolean NOT NULL,
    updated_at timestamp without time zone NOT NULL
);

CREATE SEQUENCE public.payment_type_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.payment_type_id_seq OWNED BY public.payment_type.id;

CREATE TABLE public.permission (
    id integer NOT NULL,
    code character varying(50) NOT NULL,
    name character varying(100) NOT NULL
);

CREATE SEQUENCE public.permission_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.permission_id_seq OWNED BY public.permission.id;

CREATE TABLE public.print_job_history (
    id bigint NOT NULL,
    print_type character varying(20) NOT NULL,
    printer_type character varying(20) NOT NULL,
    document_id character varying(100) NOT NULL,
    status character varying(20) NOT NULL,
    error_message text,
    printed_at timestamp without time zone NOT NULL,
    printed_by character varying(100) NOT NULL,
    printer_location_id bigint
);

CREATE SEQUENCE public.print_job_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.print_job_history_id_seq OWNED BY public.print_job_history.id;

CREATE TABLE public.printer_location (
    id bigint NOT NULL,
    printer_location character varying(255) NOT NULL,
    print_bill boolean NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    language character varying(20) DEFAULT 'GERMAN'::character varying NOT NULL,
    beep_mode character varying(20) DEFAULT 'DISABLED'::character varying NOT NULL,
    printer_setup_id bigint NOT NULL
);

CREATE SEQUENCE public.printer_location_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.printer_location_id_seq OWNED BY public.printer_location.id;

CREATE TABLE public.printer_setup (
    id bigint NOT NULL,
    printer_type character varying(50) NOT NULL,
    printer_name character varying(255),
    printer_ip character varying(500),
    printer_port integer,
    default_printer boolean,
    ipp_document_format character varying(20)
);

CREATE SEQUENCE public.printer_setup_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.printer_setup_id_seq OWNED BY public.printer_setup.id;

CREATE TABLE public.product (
    id bigint NOT NULL,
    product_number character varying(100) NOT NULL,
    name character varying(100) NOT NULL,
    en_name character varying(100),
    ch_name character varying(100),
    description text,
    price numeric(19,4) NOT NULL,
    category_id integer NOT NULL,
    product_type character varying(50) NOT NULL,
    product_code character varying(100),
    is_active boolean DEFAULT true NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    is_deposit boolean DEFAULT false NOT NULL,
    is_deposit_return boolean DEFAULT false NOT NULL,
    allow_custom_price boolean DEFAULT false NOT NULL,
    image_url character varying(500)
);

CREATE TABLE public.product_category (
    id integer NOT NULL,
    name character varying(100) NOT NULL,
    en_name character varying(100),
    ch_name character varying(100),
    description text,
    is_active boolean DEFAULT true NOT NULL,
    sort_position numeric(8,5) NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    visibility character varying(50) DEFAULT 'EVERYWHERE'::character varying NOT NULL
);

CREATE SEQUENCE public.product_category_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.product_category_id_seq OWNED BY public.product_category.id;

CREATE TABLE public.product_category_printer_location (
    product_category_id integer NOT NULL,
    printer_location_id bigint NOT NULL
);

CREATE TABLE public.product_extras (
    id integer NOT NULL,
    name character varying(255) NOT NULL,
    en_name character varying(255),
    ch_name character varying(255),
    price numeric(19,4) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    updated_at timestamp without time zone NOT NULL
);

CREATE TABLE public.product_extras_assignment (
    product_id bigint NOT NULL,
    extras_id integer NOT NULL,
    updated_at timestamp without time zone DEFAULT '2026-10-02 09:44:01.019'::timestamp without time zone NOT NULL
);

CREATE SEQUENCE public.product_extras_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.product_extras_id_seq OWNED BY public.product_extras.id;

CREATE SEQUENCE public.product_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.product_id_seq OWNED BY public.product.id;

CREATE TABLE public.promotion (
    id bigint NOT NULL,
    name character varying(200) NOT NULL,
    description character varying(1000),
    discount_type character varying(20) NOT NULL,
    discount_value numeric(19,4) NOT NULL,
    scope character varying(20) NOT NULL,
    target_product_id bigint,
    is_multi_use boolean DEFAULT false NOT NULL,
    valid_from timestamp without time zone NOT NULL,
    valid_until timestamp without time zone,
    is_active boolean DEFAULT true NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp without time zone NOT NULL
);

CREATE SEQUENCE public.promotion_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.promotion_id_seq OWNED BY public.promotion.id;

CREATE TABLE public.r_table (
    table_id bigint NOT NULL,
    table_number character varying(10) NOT NULL,
    seats integer,
    location_type_id bigint NOT NULL,
    table_status character varying(50) DEFAULT 'FREE'::character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    current_table_session_id bigint,
    position_x real,
    position_y real,
    rotation real,
    width real,
    height real,
    is_active boolean DEFAULT true NOT NULL,
    assignment_priority integer DEFAULT 0 NOT NULL
);

CREATE SEQUENCE public.r_table_table_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.r_table_table_id_seq OWNED BY public.r_table.table_id;

CREATE TABLE public.reservation_rules (
    id bigint NOT NULL,
    rule_date date NOT NULL,
    rule_type character varying(20) NOT NULL,
    duration_minutes integer,
    time_slots text,
    start_time time without time zone,
    end_time time without time zone,
    created_at timestamp without time zone NOT NULL
);

CREATE SEQUENCE public.reservation_rules_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.reservation_rules_id_seq OWNED BY public.reservation_rules.id;

CREATE TABLE public.reservation_table_assignments (
    id bigint NOT NULL,
    reservation_id bigint NOT NULL,
    table_id bigint NOT NULL,
    group_number integer NOT NULL,
    assigned_by uuid NOT NULL,
    assigned_at timestamp without time zone NOT NULL
);

CREATE SEQUENCE public.reservation_table_assignments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.reservation_table_assignments_id_seq OWNED BY public.reservation_table_assignments.id;

CREATE TABLE public.reservations (
    id bigint NOT NULL,
    customer_id uuid,
    guest_name character varying(100) NOT NULL,
    guest_phone character varying(20),
    party_size integer NOT NULL,
    reservation_date date NOT NULL,
    reservation_time time without time zone NOT NULL,
    duration_minutes integer,
    status character varying(20) DEFAULT 'CONFIRMED'::character varying NOT NULL,
    notes character varying(500),
    created_by uuid NOT NULL,
    updated_by uuid,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    table_groups integer DEFAULT 1 NOT NULL,
    max_tables_per_group integer,
    location_preference character varying(20)
);

CREATE SEQUENCE public.reservations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.reservations_id_seq OWNED BY public.reservations.id;

CREATE TABLE public.room_element (
    element_id bigint NOT NULL,
    element_type character varying(50) NOT NULL,
    position_x real NOT NULL,
    position_y real NOT NULL,
    width real NOT NULL,
    height real NOT NULL,
    rotation real DEFAULT 0.0 NOT NULL,
    location_type_id bigint NOT NULL,
    metadata text,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL
);

CREATE SEQUENCE public.room_element_element_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.room_element_element_id_seq OWNED BY public.room_element.element_id;

CREATE TABLE public.scheduled_jobs (
    id uuid NOT NULL,
    name text NOT NULL,
    type text NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    frequency text NOT NULL,
    time_of_day text NOT NULL,
    day_of_week integer,
    day_of_month integer,
    parameters_json text DEFAULT '{}'::text NOT NULL,
    timezone text DEFAULT 'Europe/Berlin'::text NOT NULL,
    next_run_at timestamp without time zone NOT NULL,
    last_run_at timestamp without time zone,
    last_status text,
    created_at timestamp without time zone DEFAULT '2026-10-02 09:44:01.059'::timestamp without time zone NOT NULL,
    updated_at timestamp without time zone DEFAULT '2026-10-02 09:44:01.059'::timestamp without time zone NOT NULL
);

CREATE TABLE public.shop_configuration (
    shop_id bigint NOT NULL,
    key character varying(100) NOT NULL,
    value jsonb NOT NULL,
    description text,
    updated_at timestamp without time zone
);

CREATE TABLE public.shop_info (
    shop_id bigint NOT NULL,
    shop_name character varying(255) NOT NULL,
    company_name character varying(255),
    shop_address character varying(500) NOT NULL,
    shop_city character varying(100) NOT NULL,
    zip_code integer NOT NULL,
    shop_country character varying(100) NOT NULL,
    shop_phone character varying(50) NOT NULL,
    shop_tax_number character varying(50),
    shop_bank_name character varying(50),
    shop_bank_bic character varying(50),
    shop_bank_iban character varying(50),
    shop_email character varying(255),
    shop_timezone character varying(50) DEFAULT 'Europe/Berlin'::character varying NOT NULL,
    shop_website character varying(500),
    restaurant_mode boolean DEFAULT true NOT NULL,
    kiosk_mode boolean DEFAULT false NOT NULL,
    logo_url character varying(500),
    create_at timestamp without time zone NOT NULL
);

CREATE SEQUENCE public.shop_info_shop_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.shop_info_shop_id_seq OWNED BY public.shop_info.shop_id;

CREATE TABLE public.t_order (
    id bigint NOT NULL,
    order_number character varying(100) NOT NULL,
    employee_id uuid NOT NULL,
    table_session_id bigint NOT NULL,
    order_status character varying(50) NOT NULL,
    order_time timestamp without time zone NOT NULL,
    order_total numeric(19,4) NOT NULL,
    tse_transaction_id bigint,
    tse_signing_status character varying(20) DEFAULT 'SIGNED'::character varying NOT NULL,
    is_training boolean DEFAULT false NOT NULL,
    terminal_id bigint
);

CREATE SEQUENCE public.t_order_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.t_order_id_seq OWNED BY public.t_order.id;

CREATE TABLE public.t_order_item (
    id bigint NOT NULL,
    orders_id bigint,
    table_session_id bigint NOT NULL,
    bill_id bigint,
    product_id bigint NOT NULL,
    unit_price numeric(19,4) NOT NULL,
    final_price numeric(19,4) DEFAULT 0 NOT NULL,
    item_status character varying(50) NOT NULL,
    vat_id character varying(10) NOT NULL,
    discount numeric(4,2) DEFAULT 1.0 NOT NULL,
    custom_price boolean DEFAULT false NOT NULL,
    take_away boolean NOT NULL,
    transferred boolean DEFAULT false NOT NULL,
    side_notes character varying(10000),
    duplicated_from_bill_id bigint,
    "timestamp" timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE SEQUENCE public.t_order_item_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.t_order_item_id_seq OWNED BY public.t_order_item.id;

CREATE TABLE public.table_session (
    table_session_id bigint NOT NULL,
    table_id bigint NOT NULL,
    employee_id uuid NOT NULL,
    start_time timestamp without time zone DEFAULT '2026-10-02 09:44:01.013'::timestamp without time zone NOT NULL,
    end_time timestamp without time zone,
    completed boolean DEFAULT false NOT NULL,
    zbon_id bigint,
    note character varying(500),
    session_type character varying(20) DEFAULT 'DINING'::character varying NOT NULL,
    tse_transaction_id bigint,
    is_training boolean DEFAULT false NOT NULL,
    delivery boolean DEFAULT false NOT NULL,
    customer_id uuid,
    delivery_address_id uuid,
    delivery_phone_number character varying(20)
);

CREATE SEQUENCE public.table_session_table_session_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.table_session_table_session_id_seq OWNED BY public.table_session.table_session_id;

CREATE TABLE public.tax_group (
    id bigint NOT NULL,
    vat_id character varying(10) NOT NULL,
    net_amount numeric(19,4) NOT NULL,
    tax_amount numeric(19,4) NOT NULL,
    gross_amount numeric(19,4) NOT NULL,
    zbon_id bigint NOT NULL
);

CREATE SEQUENCE public.tax_group_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.tax_group_id_seq OWNED BY public.tax_group.id;

CREATE TABLE public.terminals (
    id bigint NOT NULL,
    device_id character varying(100) NOT NULL,
    terminal_id character varying(50) NOT NULL,
    terminal_brand character varying(100),
    terminal_model character varying(100),
    terminal_serial_nr character varying(100),
    terminal_sw_brand character varying(100) DEFAULT 'POS-KMP'::character varying NOT NULL,
    terminal_sw_version character varying(50),
    is_active boolean DEFAULT true NOT NULL,
    tse_client_registered boolean DEFAULT false NOT NULL,
    shop_info_id bigint,
    registered_at timestamp without time zone NOT NULL,
    last_seen_at timestamp without time zone NOT NULL
);

CREATE SEQUENCE public.terminals_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.terminals_id_seq OWNED BY public.terminals.id;

CREATE TABLE public.time_approval (
    id uuid NOT NULL,
    employee_id uuid NOT NULL,
    approved_by uuid,
    period_year integer NOT NULL,
    period_month integer NOT NULL,
    submitted_at timestamp without time zone DEFAULT '2026-10-02 09:44:01.058'::timestamp without time zone NOT NULL,
    approved_at timestamp without time zone,
    status character varying(20) DEFAULT 'SUBMITTED'::character varying NOT NULL,
    rejection_reason text
);

CREATE TABLE public.time_break (
    id uuid NOT NULL,
    time_entry_id uuid NOT NULL,
    break_start timestamp without time zone NOT NULL,
    break_end timestamp without time zone
);

CREATE TABLE public.time_entry (
    id uuid NOT NULL,
    employee_id uuid NOT NULL,
    date date NOT NULL,
    clock_in timestamp without time zone NOT NULL,
    clock_out timestamp without time zone,
    manual_entry boolean DEFAULT false NOT NULL,
    cost_center_id uuid,
    comment text,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT '2026-10-02 09:44:01.058'::timestamp without time zone NOT NULL,
    updated_at timestamp without time zone DEFAULT '2026-10-02 09:44:01.058'::timestamp without time zone NOT NULL
);

CREATE TABLE public.transaction_audit_log (
    id bigint NOT NULL,
    transaction_id character varying(100) NOT NULL,
    employee_id character varying(100) NOT NULL,
    employee_number character varying(50) NOT NULL,
    authorization_type character varying(50) NOT NULL,
    authorizing_manager_id character varying(100),
    authorizing_manager_name character varying(255),
    operation character varying(100) NOT NULL,
    entity_type character varying(50) NOT NULL,
    entity_id character varying(50) NOT NULL,
    entity_name character varying(255),
    before_state text,
    after_state text,
    changes_summary text,
    device_id character varying(100),
    ip_address character varying(50),
    reason text,
    amount_impact numeric(10,2),
    currency_code character varying(3) DEFAULT 'EUR'::character varying NOT NULL,
    metadata text,
    "timestamp" character varying(50) NOT NULL,
    fiscal_record_id character varying(100),
    created_at character varying(50) NOT NULL
);

CREATE SEQUENCE public.transaction_audit_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.transaction_audit_log_id_seq OWNED BY public.transaction_audit_log.id;

CREATE TABLE public.tse_signing_queue (
    id bigint NOT NULL,
    reference_id character varying(100) NOT NULL,
    entity_type character varying(20) NOT NULL,
    process_type character varying(50) NOT NULL,
    process_data text NOT NULL,
    client_id character varying(100) NOT NULL,
    signing_status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    max_retries integer DEFAULT 10 NOT NULL,
    last_error text,
    last_error_code character varying(50),
    created_at timestamp without time zone NOT NULL,
    last_attempt_at timestamp without time zone,
    signed_at timestamp without time zone,
    next_retry_at timestamp without time zone,
    priority integer DEFAULT 5 NOT NULL,
    tse_transaction_id bigint
);

CREATE SEQUENCE public.tse_signing_queue_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.tse_signing_queue_id_seq OWNED BY public.tse_signing_queue.id;

CREATE TABLE public.tse_transaction (
    id bigint NOT NULL,
    tse_serial_number character varying(100),
    transaction_number bigint,
    process_type character varying(100),
    process_data_hash character varying(500),
    signature character varying(1000),
    start_time timestamp without time zone,
    end_time timestamp without time zone,
    client_id character varying(100),
    tse_mode character varying(20) DEFAULT 'SIMULATION'::character varying NOT NULL,
    exported_file_reference character varying(500),
    tse_timestamp bigint,
    signature_counter bigint,
    signature_algorithm character varying(100),
    public_key text,
    certificate text
);

CREATE SEQUENCE public.tse_transaction_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.tse_transaction_id_seq OWNED BY public.tse_transaction.id;

CREATE TABLE public.vat (
    id character varying(10) NOT NULL,
    vat_class character varying(50) NOT NULL,
    vat_rate numeric(5,4) NOT NULL
);

CREATE TABLE public.voucher (
    id bigint NOT NULL,
    order_item_id bigint NOT NULL,
    voucher_number character varying(50) NOT NULL,
    original_value numeric(10,2) NOT NULL,
    remaining_value numeric(10,2) NOT NULL,
    created_at timestamp without time zone NOT NULL,
    fully_redeemed_at timestamp without time zone,
    zbon_id bigint,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    invalidation_reason character varying(1000),
    invalidated_at timestamp without time zone,
    invalidation_source character varying(30)
);

CREATE SEQUENCE public.voucher_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.voucher_id_seq OWNED BY public.voucher.id;

CREATE TABLE public.voucher_redeem (
    id bigint NOT NULL,
    voucher_id bigint,
    payment_id bigint NOT NULL,
    redeem_amount numeric(10,2) NOT NULL,
    redeemed_at timestamp without time zone NOT NULL,
    voucher_type character varying(40) NOT NULL,
    voucher_number character varying(50) NOT NULL,
    zbon_id bigint
);

CREATE SEQUENCE public.voucher_redeem_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.voucher_redeem_id_seq OWNED BY public.voucher_redeem.id;

CREATE TABLE public.wage_type (
    id uuid NOT NULL,
    code integer NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(255),
    is_default boolean DEFAULT false NOT NULL
);

CREATE TABLE public.work_schedules (
    id uuid NOT NULL,
    employee_id uuid NOT NULL,
    day_of_week integer NOT NULL,
    start_time character varying(5) NOT NULL,
    end_time character varying(5) NOT NULL,
    break_duration integer DEFAULT 30 NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL
);

CREATE TABLE public.zbon (
    zbon_id bigint NOT NULL,
    shop_info_id bigint NOT NULL,
    employee_id uuid NOT NULL,
    start_time timestamp without time zone NOT NULL,
    end_time timestamp without time zone NOT NULL,
    cancelled_amount numeric(19,4) NOT NULL,
    total_discounts numeric(19,4) DEFAULT 0 NOT NULL,
    total_pos_revenue numeric(19,4) NOT NULL,
    admin_tip_revenue numeric(19,4) DEFAULT 0 NOT NULL,
    opening_balance numeric(19,4) NOT NULL,
    expenses numeric(19,4) NOT NULL,
    cash_revenue numeric(19,4) NOT NULL,
    closing_balance numeric(19,4) NOT NULL,
    created_at timestamp without time zone NOT NULL,
    training_revenue numeric(19,4) DEFAULT 0 NOT NULL,
    training_bill_count integer DEFAULT 0 NOT NULL,
    refund_amount numeric(19,4) DEFAULT 0 NOT NULL,
    storno_bill_count integer DEFAULT 0 NOT NULL,
    zbon_nummer bigint DEFAULT 0 NOT NULL
);

CREATE TABLE public.zbon_payment_summary (
    id bigint NOT NULL,
    zbon_id bigint NOT NULL,
    payment_type_name bigint NOT NULL,
    currency character varying(3) NOT NULL,
    amount numeric(19,4) NOT NULL
);

CREATE SEQUENCE public.zbon_payment_summary_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.zbon_payment_summary_id_seq OWNED BY public.zbon_payment_summary.id;

CREATE SEQUENCE public.zbon_zbon_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.zbon_zbon_id_seq OWNED BY public.zbon.zbon_id;

ALTER TABLE ONLY public.bill ALTER COLUMN id SET DEFAULT nextval('public.bill_id_seq'::regclass);

ALTER TABLE ONLY public.bill_promotion ALTER COLUMN id SET DEFAULT nextval('public.bill_promotion_id_seq'::regclass);

ALTER TABLE ONLY public.bill_vat_detail ALTER COLUMN id SET DEFAULT nextval('public.bill_vat_detail_id_seq'::regclass);

ALTER TABLE ONLY public.cancellation ALTER COLUMN id SET DEFAULT nextval('public.cancellation_id_seq'::regclass);

ALTER TABLE ONLY public.card_settlement ALTER COLUMN id SET DEFAULT nextval('public.card_settlement_id_seq'::regclass);

ALTER TABLE ONLY public.card_settlement_detail ALTER COLUMN id SET DEFAULT nextval('public.card_settlement_detail_id_seq'::regclass);

ALTER TABLE ONLY public.card_terminal ALTER COLUMN id SET DEFAULT nextval('public.card_terminal_id_seq'::regclass);

ALTER TABLE ONLY public.card_transaction ALTER COLUMN id SET DEFAULT nextval('public.card_transaction_id_seq'::regclass);

ALTER TABLE ONLY public.datev_export_log ALTER COLUMN id SET DEFAULT nextval('public.datev_export_log_id_seq'::regclass);

ALTER TABLE ONLY public.document_bank_statement_metadata ALTER COLUMN id SET DEFAULT nextval('public.document_bank_statement_metadata_id_seq'::regclass);

ALTER TABLE ONLY public.document_invoice_metadata ALTER COLUMN id SET DEFAULT nextval('public.document_invoice_metadata_id_seq'::regclass);

ALTER TABLE ONLY public.document_links ALTER COLUMN id SET DEFAULT nextval('public.document_links_id_seq'::regclass);

ALTER TABLE ONLY public.documents ALTER COLUMN id SET DEFAULT nextval('public.documents_id_seq'::regclass);

ALTER TABLE ONLY public.employee_info ALTER COLUMN employee_info_id SET DEFAULT nextval('public.employee_info_employee_info_id_seq'::regclass);

ALTER TABLE ONLY public.employee_zbon_payment_breakdown ALTER COLUMN id SET DEFAULT nextval('public.employee_zbon_payment_breakdown_id_seq'::regclass);

ALTER TABLE ONLY public.employee_zbon_summary ALTER COLUMN id SET DEFAULT nextval('public.employee_zbon_summary_id_seq'::regclass);

ALTER TABLE ONLY public.entry_log ALTER COLUMN id SET DEFAULT nextval('public.entry_log_id_seq'::regclass);

ALTER TABLE ONLY public.favourite_product ALTER COLUMN id SET DEFAULT nextval('public.favourite_product_id_seq'::regclass);

ALTER TABLE ONLY public.item_transfer ALTER COLUMN id SET DEFAULT nextval('public.item_transfer_id_seq'::regclass);

ALTER TABLE ONLY public.kassenbuch_header ALTER COLUMN id SET DEFAULT nextval('public.kassenbuch_header_id_seq'::regclass);

ALTER TABLE ONLY public.kassenbuch_line_item ALTER COLUMN id SET DEFAULT nextval('public.kassenbuch_line_item_id_seq'::regclass);

ALTER TABLE ONLY public.location_types ALTER COLUMN id SET DEFAULT nextval('public.location_types_id_seq'::regclass);

ALTER TABLE ONLY public.opening_hours ALTER COLUMN id SET DEFAULT nextval('public.opening_hours_id_seq'::regclass);

ALTER TABLE ONLY public.opening_hours_override ALTER COLUMN id SET DEFAULT nextval('public.opening_hours_override_id_seq'::regclass);

ALTER TABLE ONLY public.payment ALTER COLUMN id SET DEFAULT nextval('public.payment_id_seq'::regclass);

ALTER TABLE ONLY public.payment_type ALTER COLUMN id SET DEFAULT nextval('public.payment_type_id_seq'::regclass);

ALTER TABLE ONLY public.permission ALTER COLUMN id SET DEFAULT nextval('public.permission_id_seq'::regclass);

ALTER TABLE ONLY public.print_job_history ALTER COLUMN id SET DEFAULT nextval('public.print_job_history_id_seq'::regclass);

ALTER TABLE ONLY public.printer_location ALTER COLUMN id SET DEFAULT nextval('public.printer_location_id_seq'::regclass);

ALTER TABLE ONLY public.printer_setup ALTER COLUMN id SET DEFAULT nextval('public.printer_setup_id_seq'::regclass);

ALTER TABLE ONLY public.product ALTER COLUMN id SET DEFAULT nextval('public.product_id_seq'::regclass);

ALTER TABLE ONLY public.product_category ALTER COLUMN id SET DEFAULT nextval('public.product_category_id_seq'::regclass);

ALTER TABLE ONLY public.product_extras ALTER COLUMN id SET DEFAULT nextval('public.product_extras_id_seq'::regclass);

ALTER TABLE ONLY public.promotion ALTER COLUMN id SET DEFAULT nextval('public.promotion_id_seq'::regclass);

ALTER TABLE ONLY public.r_table ALTER COLUMN table_id SET DEFAULT nextval('public.r_table_table_id_seq'::regclass);

ALTER TABLE ONLY public.reservation_rules ALTER COLUMN id SET DEFAULT nextval('public.reservation_rules_id_seq'::regclass);

ALTER TABLE ONLY public.reservation_table_assignments ALTER COLUMN id SET DEFAULT nextval('public.reservation_table_assignments_id_seq'::regclass);

ALTER TABLE ONLY public.reservations ALTER COLUMN id SET DEFAULT nextval('public.reservations_id_seq'::regclass);

ALTER TABLE ONLY public.room_element ALTER COLUMN element_id SET DEFAULT nextval('public.room_element_element_id_seq'::regclass);

ALTER TABLE ONLY public.shop_info ALTER COLUMN shop_id SET DEFAULT nextval('public.shop_info_shop_id_seq'::regclass);

ALTER TABLE ONLY public.t_order ALTER COLUMN id SET DEFAULT nextval('public.t_order_id_seq'::regclass);

ALTER TABLE ONLY public.t_order_item ALTER COLUMN id SET DEFAULT nextval('public.t_order_item_id_seq'::regclass);

ALTER TABLE ONLY public.table_session ALTER COLUMN table_session_id SET DEFAULT nextval('public.table_session_table_session_id_seq'::regclass);

ALTER TABLE ONLY public.tax_group ALTER COLUMN id SET DEFAULT nextval('public.tax_group_id_seq'::regclass);

ALTER TABLE ONLY public.terminals ALTER COLUMN id SET DEFAULT nextval('public.terminals_id_seq'::regclass);

ALTER TABLE ONLY public.transaction_audit_log ALTER COLUMN id SET DEFAULT nextval('public.transaction_audit_log_id_seq'::regclass);

ALTER TABLE ONLY public.tse_signing_queue ALTER COLUMN id SET DEFAULT nextval('public.tse_signing_queue_id_seq'::regclass);

ALTER TABLE ONLY public.tse_transaction ALTER COLUMN id SET DEFAULT nextval('public.tse_transaction_id_seq'::regclass);

ALTER TABLE ONLY public.voucher ALTER COLUMN id SET DEFAULT nextval('public.voucher_id_seq'::regclass);

ALTER TABLE ONLY public.voucher_redeem ALTER COLUMN id SET DEFAULT nextval('public.voucher_redeem_id_seq'::regclass);

ALTER TABLE ONLY public.zbon ALTER COLUMN zbon_id SET DEFAULT nextval('public.zbon_zbon_id_seq'::regclass);

ALTER TABLE ONLY public.zbon_payment_summary ALTER COLUMN id SET DEFAULT nextval('public.zbon_payment_summary_id_seq'::regclass);

ALTER TABLE ONLY public.bill
    ADD CONSTRAINT bill_bill_number_unique UNIQUE (bill_number);

ALTER TABLE ONLY public.bill
    ADD CONSTRAINT bill_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.bill_promotion
    ADD CONSTRAINT bill_promotion_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.bill_vat_detail
    ADD CONSTRAINT bill_vat_detail_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cancellation
    ADD CONSTRAINT cancellation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.card_settlement_detail
    ADD CONSTRAINT card_settlement_detail_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.card_settlement
    ADD CONSTRAINT card_settlement_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.card_terminal
    ADD CONSTRAINT card_terminal_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.card_transaction
    ADD CONSTRAINT card_transaction_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cost_center
    ADD CONSTRAINT cost_center_code_unique UNIQUE (code);

ALTER TABLE ONLY public.cost_center
    ADD CONSTRAINT cost_center_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.customer_addresses
    ADD CONSTRAINT customer_addresses_pkey PRIMARY KEY (address_id);

ALTER TABLE ONLY public.customers
    ADD CONSTRAINT customers_customer_number_unique UNIQUE (customer_number);

ALTER TABLE ONLY public.customers
    ADD CONSTRAINT customers_email_unique UNIQUE (email);

ALTER TABLE ONLY public.customers
    ADD CONSTRAINT customers_phone_unique UNIQUE (phone);

ALTER TABLE ONLY public.customers
    ADD CONSTRAINT customers_pkey PRIMARY KEY (customer_id);

ALTER TABLE ONLY public.datev_export_log
    ADD CONSTRAINT datev_export_log_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.document_bank_statement_metadata
    ADD CONSTRAINT document_bank_statement_metadata_pdf_document_id_unique UNIQUE (pdf_document_id);

ALTER TABLE ONLY public.document_bank_statement_metadata
    ADD CONSTRAINT document_bank_statement_metadata_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.document_invoice_metadata
    ADD CONSTRAINT document_invoice_metadata_pdf_document_id_unique UNIQUE (pdf_document_id);

ALTER TABLE ONLY public.document_invoice_metadata
    ADD CONSTRAINT document_invoice_metadata_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.document_links
    ADD CONSTRAINT document_links_document_id_bank_statement_document_id_unique UNIQUE (document_id, bank_statement_document_id);

ALTER TABLE ONLY public.document_links
    ADD CONSTRAINT document_links_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_file_path_unique UNIQUE (file_path);

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.employee_info
    ADD CONSTRAINT employee_info_email_unique UNIQUE (email);

ALTER TABLE ONLY public.employee_info
    ADD CONSTRAINT employee_info_pkey PRIMARY KEY (employee_info_id);

ALTER TABLE ONLY public.employee_zbon_payment_breakdown
    ADD CONSTRAINT employee_zbon_payment_breakdown_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.employee_zbon_summary
    ADD CONSTRAINT employee_zbon_summary_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.employees
    ADD CONSTRAINT employees_employee_number_unique UNIQUE (employee_number);

ALTER TABLE ONLY public.employees
    ADD CONSTRAINT employees_pkey PRIMARY KEY (employee_id);

ALTER TABLE ONLY public.employees
    ADD CONSTRAINT employees_username_unique UNIQUE (username);

ALTER TABLE ONLY public.entry_log
    ADD CONSTRAINT entry_log_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.favourite_product
    ADD CONSTRAINT favourite_product_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.item_transfer
    ADD CONSTRAINT item_transfer_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.job_executions
    ADD CONSTRAINT job_executions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.kassenbuch_header
    ADD CONSTRAINT kassenbuch_header_linked_document_id_unique UNIQUE (linked_document_id);

ALTER TABLE ONLY public.kassenbuch_header
    ADD CONSTRAINT kassenbuch_header_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.kassenbuch_line_item
    ADD CONSTRAINT kassenbuch_line_item_header_id_line_number_unique UNIQUE (header_id, line_number);

ALTER TABLE ONLY public.kassenbuch_line_item
    ADD CONSTRAINT kassenbuch_line_item_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.llm_prompts
    ADD CONSTRAINT llm_prompts_document_type_unique UNIQUE (document_type);

ALTER TABLE ONLY public.llm_prompts
    ADD CONSTRAINT llm_prompts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.location_types
    ADD CONSTRAINT location_types_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.opening_hours_override
    ADD CONSTRAINT opening_hours_override_override_date_shop_id_unique UNIQUE (override_date, shop_id);

ALTER TABLE ONLY public.opening_hours_override
    ADD CONSTRAINT opening_hours_override_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.opening_hours
    ADD CONSTRAINT opening_hours_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.payment
    ADD CONSTRAINT payment_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.payment_type
    ADD CONSTRAINT payment_type_name_unique UNIQUE (name);

ALTER TABLE ONLY public.payment_type
    ADD CONSTRAINT payment_type_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.permission
    ADD CONSTRAINT permission_code_unique UNIQUE (code);

ALTER TABLE ONLY public.permission
    ADD CONSTRAINT permission_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.combinable_tables
    ADD CONSTRAINT pk_combinable_tables PRIMARY KEY (table_a_id, table_b_id);

ALTER TABLE ONLY public.employee_permissions
    ADD CONSTRAINT pk_employee_permissions PRIMARY KEY (employee_id, permission_id);

ALTER TABLE ONLY public.item_extras
    ADD CONSTRAINT pk_itemextras_orderitemid_productextrasid PRIMARY KEY (order_item_id, product_extras_id);

ALTER TABLE ONLY public.product_category_printer_location
    ADD CONSTRAINT pk_product_category_printer_location PRIMARY KEY (product_category_id, printer_location_id);

ALTER TABLE ONLY public.product_extras_assignment
    ADD CONSTRAINT pk_product_extras_assignment PRIMARY KEY (product_id, extras_id);

ALTER TABLE ONLY public.shop_configuration
    ADD CONSTRAINT pk_shop_configuration PRIMARY KEY (shop_id, key);

ALTER TABLE ONLY public.print_job_history
    ADD CONSTRAINT print_job_history_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.printer_location
    ADD CONSTRAINT printer_location_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.printer_location
    ADD CONSTRAINT printer_location_printer_location_unique UNIQUE (printer_location);

ALTER TABLE ONLY public.printer_location
    ADD CONSTRAINT printer_location_printer_setup_id_unique UNIQUE (printer_setup_id);

ALTER TABLE ONLY public.printer_setup
    ADD CONSTRAINT printer_setup_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.product_category
    ADD CONSTRAINT product_category_name_unique UNIQUE (name);

ALTER TABLE ONLY public.product_category
    ADD CONSTRAINT product_category_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.product_extras
    ADD CONSTRAINT product_extras_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.product
    ADD CONSTRAINT product_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.product
    ADD CONSTRAINT product_product_code_unique UNIQUE (product_code);

ALTER TABLE ONLY public.product
    ADD CONSTRAINT product_product_number_unique UNIQUE (product_number);

ALTER TABLE ONLY public.promotion
    ADD CONSTRAINT promotion_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.r_table
    ADD CONSTRAINT r_table_pkey PRIMARY KEY (table_id);

ALTER TABLE ONLY public.r_table
    ADD CONSTRAINT r_table_table_number_unique UNIQUE (table_number);

ALTER TABLE ONLY public.reservation_rules
    ADD CONSTRAINT reservation_rules_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.reservation_rules
    ADD CONSTRAINT reservation_rules_rule_date_unique UNIQUE (rule_date);

ALTER TABLE ONLY public.reservation_table_assignments
    ADD CONSTRAINT reservation_table_assignments_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT reservations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.room_element
    ADD CONSTRAINT room_element_pkey PRIMARY KEY (element_id);

ALTER TABLE ONLY public.scheduled_jobs
    ADD CONSTRAINT scheduled_jobs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.shop_configuration
    ADD CONSTRAINT shop_configuration_shop_id_key_unique UNIQUE (shop_id, key);

ALTER TABLE ONLY public.shop_info
    ADD CONSTRAINT shop_info_pkey PRIMARY KEY (shop_id);

ALTER TABLE ONLY public.t_order_item
    ADD CONSTRAINT t_order_item_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.t_order
    ADD CONSTRAINT t_order_order_number_unique UNIQUE (order_number);

ALTER TABLE ONLY public.t_order
    ADD CONSTRAINT t_order_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.table_session
    ADD CONSTRAINT table_session_pkey PRIMARY KEY (table_session_id);

ALTER TABLE ONLY public.tax_group
    ADD CONSTRAINT tax_group_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.terminals
    ADD CONSTRAINT terminals_device_id_unique UNIQUE (device_id);

ALTER TABLE ONLY public.terminals
    ADD CONSTRAINT terminals_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.time_approval
    ADD CONSTRAINT time_approval_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.time_break
    ADD CONSTRAINT time_break_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.time_entry
    ADD CONSTRAINT time_entry_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.transaction_audit_log
    ADD CONSTRAINT transaction_audit_log_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.transaction_audit_log
    ADD CONSTRAINT transaction_audit_log_transaction_id_unique UNIQUE (transaction_id);

ALTER TABLE ONLY public.tse_signing_queue
    ADD CONSTRAINT tse_signing_queue_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.tse_transaction
    ADD CONSTRAINT tse_transaction_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.tse_transaction
    ADD CONSTRAINT tse_transaction_transaction_number_unique UNIQUE (transaction_number);

ALTER TABLE ONLY public.time_approval
    ADD CONSTRAINT uq_approval_employee_period UNIQUE (employee_id, period_year, period_month);

ALTER TABLE ONLY public.reservation_table_assignments
    ADD CONSTRAINT uq_reservation_table UNIQUE (reservation_id, table_id);

ALTER TABLE ONLY public.zbon
    ADD CONSTRAINT uq_zbon_shop_nummer UNIQUE (shop_info_id, zbon_nummer);

ALTER TABLE ONLY public.vat
    ADD CONSTRAINT vat_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.vat
    ADD CONSTRAINT vat_vat_class_unique UNIQUE (vat_class);

ALTER TABLE ONLY public.voucher
    ADD CONSTRAINT voucher_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.voucher_redeem
    ADD CONSTRAINT voucher_redeem_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.voucher
    ADD CONSTRAINT voucher_voucher_number_unique UNIQUE (voucher_number);

ALTER TABLE ONLY public.wage_type
    ADD CONSTRAINT wage_type_code_unique UNIQUE (code);

ALTER TABLE ONLY public.wage_type
    ADD CONSTRAINT wage_type_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.work_schedules
    ADD CONSTRAINT work_schedules_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.zbon_payment_summary
    ADD CONSTRAINT zbon_payment_summary_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.zbon
    ADD CONSTRAINT zbon_pkey PRIMARY KEY (zbon_id);

CREATE INDEX document_links_bank_statement_document_id ON public.document_links USING btree (bank_statement_document_id);

CREATE INDEX document_links_document_id ON public.document_links USING btree (document_id);

CREATE INDEX document_links_linked_at ON public.document_links USING btree (linked_at);

CREATE INDEX document_links_linked_by ON public.document_links USING btree (linked_by);

CREATE INDEX documents_content_hash ON public.documents USING btree (content_hash);

CREATE INDEX documents_file_type ON public.documents USING btree (file_type);

CREATE INDEX documents_file_type_uploaded_at ON public.documents USING btree (file_type, uploaded_at);

CREATE INDEX documents_is_active ON public.documents USING btree (is_active);

CREATE INDEX documents_media_type ON public.documents USING btree (media_type);

CREATE INDEX documents_uploaded_at ON public.documents USING btree (uploaded_at);

CREATE INDEX documents_uploaded_by ON public.documents USING btree (uploaded_by);

CREATE INDEX idx_rta_reservation ON public.reservation_table_assignments USING btree (reservation_id);

CREATE INDEX idx_rta_table ON public.reservation_table_assignments USING btree (table_id);

CREATE INDEX kassenbuch_header_beleg_nr ON public.kassenbuch_header USING btree (beleg_nr);

CREATE INDEX kassenbuch_header_created_at ON public.kassenbuch_header USING btree (created_at);

CREATE INDEX kassenbuch_header_employee_id ON public.kassenbuch_header USING btree (employee_id);

CREATE INDEX kassenbuch_header_shop_id ON public.kassenbuch_header USING btree (shop_id);

CREATE INDEX kassenbuch_line_item_header_id ON public.kassenbuch_line_item USING btree (header_id);

CREATE INDEX print_job_history_document_id ON public.print_job_history USING btree (document_id);

CREATE INDEX print_job_history_printed_at ON public.print_job_history USING btree (printed_at);

CREATE INDEX print_job_history_printer_location_id ON public.print_job_history USING btree (printer_location_id);

CREATE INDEX print_job_history_status ON public.print_job_history USING btree (status);

CREATE INDEX transaction_audit_log_authorizing_manager_id ON public.transaction_audit_log USING btree (authorizing_manager_id);

CREATE INDEX transaction_audit_log_device_id ON public.transaction_audit_log USING btree (device_id);

CREATE INDEX transaction_audit_log_employee_id ON public.transaction_audit_log USING btree (employee_id);

CREATE INDEX transaction_audit_log_entity_id ON public.transaction_audit_log USING btree (entity_id);

CREATE INDEX transaction_audit_log_entity_type ON public.transaction_audit_log USING btree (entity_type);

CREATE INDEX transaction_audit_log_operation ON public.transaction_audit_log USING btree (operation);

CREATE INDEX transaction_audit_log_timestamp ON public.transaction_audit_log USING btree ("timestamp");

ALTER TABLE ONLY public.bill
    ADD CONSTRAINT fk_bill_deleted_bill_id__id FOREIGN KEY (deleted_bill_id) REFERENCES public.bill(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.bill
    ADD CONSTRAINT fk_bill_employee_id__employee_id FOREIGN KEY (employee_id) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.bill_promotion
    ADD CONSTRAINT fk_bill_promotion_applied_by__employee_id FOREIGN KEY (applied_by) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.bill_promotion
    ADD CONSTRAINT fk_bill_promotion_bill_id__id FOREIGN KEY (bill_id) REFERENCES public.bill(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.bill_promotion
    ADD CONSTRAINT fk_bill_promotion_order_item_id__id FOREIGN KEY (order_item_id) REFERENCES public.t_order_item(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.bill_promotion
    ADD CONSTRAINT fk_bill_promotion_promotion_id__id FOREIGN KEY (promotion_id) REFERENCES public.promotion(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.bill
    ADD CONSTRAINT fk_bill_table_session__table_session_id FOREIGN KEY (table_session) REFERENCES public.table_session(table_session_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.bill
    ADD CONSTRAINT fk_bill_terminal_id__id FOREIGN KEY (terminal_id) REFERENCES public.terminals(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.bill
    ADD CONSTRAINT fk_bill_tse_transaction_id__id FOREIGN KEY (tse_transaction_id) REFERENCES public.tse_transaction(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.bill_vat_detail
    ADD CONSTRAINT fk_bill_vat_detail_bill_id__id FOREIGN KEY (bill_id) REFERENCES public.bill(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.bill_vat_detail
    ADD CONSTRAINT fk_bill_vat_detail_vat_id__id FOREIGN KEY (vat_id) REFERENCES public.vat(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.bill
    ADD CONSTRAINT fk_bill_zbon_id__zbon_id FOREIGN KEY (zbon_id) REFERENCES public.zbon(zbon_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.cancellation
    ADD CONSTRAINT fk_cancellation_order_item_id__id FOREIGN KEY (order_item_id) REFERENCES public.t_order_item(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.cancellation
    ADD CONSTRAINT fk_cancellation_tse_transaction_id__id FOREIGN KEY (tse_transaction_id) REFERENCES public.tse_transaction(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.cancellation
    ADD CONSTRAINT fk_cancellation_user_mapping_id__employee_id FOREIGN KEY (user_mapping_id) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.cancellation
    ADD CONSTRAINT fk_cancellation_zbon_id__zbon_id FOREIGN KEY (zbon_id) REFERENCES public.zbon(zbon_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.card_settlement
    ADD CONSTRAINT fk_card_settlement_card_terminal_id__id FOREIGN KEY (card_terminal_id) REFERENCES public.card_terminal(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.card_settlement_detail
    ADD CONSTRAINT fk_card_settlement_detail_card_settlement_id__id FOREIGN KEY (card_settlement_id) REFERENCES public.card_settlement(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY public.card_settlement
    ADD CONSTRAINT fk_card_settlement_zbon_id__zbon_id FOREIGN KEY (zbon_id) REFERENCES public.zbon(zbon_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.card_terminal
    ADD CONSTRAINT fk_card_terminal_shop_info_id__shop_id FOREIGN KEY (shop_info_id) REFERENCES public.shop_info(shop_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.card_transaction
    ADD CONSTRAINT fk_card_transaction_card_terminal_id__id FOREIGN KEY (card_terminal_id) REFERENCES public.card_terminal(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.card_transaction
    ADD CONSTRAINT fk_card_transaction_payment_id__id FOREIGN KEY (payment_id) REFERENCES public.payment(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.combinable_tables
    ADD CONSTRAINT fk_combinable_tables_table_a_id__table_id FOREIGN KEY (table_a_id) REFERENCES public.r_table(table_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.combinable_tables
    ADD CONSTRAINT fk_combinable_tables_table_b_id__table_id FOREIGN KEY (table_b_id) REFERENCES public.r_table(table_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.customer_addresses
    ADD CONSTRAINT fk_customer_addresses_customer_id__customer_id FOREIGN KEY (customer_id) REFERENCES public.customers(customer_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.document_bank_statement_metadata
    ADD CONSTRAINT fk_document_bank_statement_metadata_pdf_document_id__id FOREIGN KEY (pdf_document_id) REFERENCES public.documents(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.document_invoice_metadata
    ADD CONSTRAINT fk_document_invoice_metadata_pdf_document_id__id FOREIGN KEY (pdf_document_id) REFERENCES public.documents(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.document_links
    ADD CONSTRAINT fk_document_links_bank_statement_document_id__id FOREIGN KEY (bank_statement_document_id) REFERENCES public.documents(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.document_links
    ADD CONSTRAINT fk_document_links_document_id__id FOREIGN KEY (document_id) REFERENCES public.documents(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT fk_documents_uploaded_by__employee_id FOREIGN KEY (uploaded_by) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.employee_info
    ADD CONSTRAINT fk_employee_info_employee_id__employee_id FOREIGN KEY (employee_id) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.employee_permissions
    ADD CONSTRAINT fk_employee_permissions_employee_id__employee_id FOREIGN KEY (employee_id) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.employee_permissions
    ADD CONSTRAINT fk_employee_permissions_permission_id__id FOREIGN KEY (permission_id) REFERENCES public.permission(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.employee_zbon_payment_breakdown
    ADD CONSTRAINT fk_employee_zbon_payment_breakdown_employee_zbon_summary_id__id FOREIGN KEY (employee_zbon_summary_id) REFERENCES public.employee_zbon_summary(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.employee_zbon_payment_breakdown
    ADD CONSTRAINT fk_employee_zbon_payment_breakdown_payment_type_id__id FOREIGN KEY (payment_type_id) REFERENCES public.payment_type(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.employee_zbon_summary
    ADD CONSTRAINT fk_employee_zbon_summary_employee_id__employee_id FOREIGN KEY (employee_id) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.employee_zbon_summary
    ADD CONSTRAINT fk_employee_zbon_summary_z_bon_id__zbon_id FOREIGN KEY (z_bon_id) REFERENCES public.zbon(zbon_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.employees
    ADD CONSTRAINT fk_employees_default_cost_center_id__id FOREIGN KEY (default_cost_center_id) REFERENCES public.cost_center(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.employees
    ADD CONSTRAINT fk_employees_info_id__employee_info_id FOREIGN KEY (info_id) REFERENCES public.employee_info(employee_info_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.entry_log
    ADD CONSTRAINT fk_entry_log_employee_id__employee_id FOREIGN KEY (employee_id) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.favourite_product
    ADD CONSTRAINT fk_favourite_product_product_id__id FOREIGN KEY (product_id) REFERENCES public.product(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.item_extras
    ADD CONSTRAINT fk_item_extras_order_item_id__id FOREIGN KEY (order_item_id) REFERENCES public.t_order_item(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.item_extras
    ADD CONSTRAINT fk_item_extras_product_extras_id__id FOREIGN KEY (product_extras_id) REFERENCES public.product_extras(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.item_transfer
    ADD CONSTRAINT fk_item_transfer_employee_id__employee_id FOREIGN KEY (employee_id) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.item_transfer
    ADD CONSTRAINT fk_item_transfer_from_table_id__table_session_id FOREIGN KEY (from_table_id) REFERENCES public.table_session(table_session_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.item_transfer
    ADD CONSTRAINT fk_item_transfer_order_item_id__id FOREIGN KEY (order_item_id) REFERENCES public.t_order_item(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.item_transfer
    ADD CONSTRAINT fk_item_transfer_to_table_id__table_session_id FOREIGN KEY (to_table_id) REFERENCES public.table_session(table_session_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.job_executions
    ADD CONSTRAINT fk_job_executions_job_id__id FOREIGN KEY (job_id) REFERENCES public.scheduled_jobs(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.kassenbuch_header
    ADD CONSTRAINT fk_kassenbuch_header_employee_id__employee_id FOREIGN KEY (employee_id) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.kassenbuch_header
    ADD CONSTRAINT fk_kassenbuch_header_linked_document_id__id FOREIGN KEY (linked_document_id) REFERENCES public.documents(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.kassenbuch_header
    ADD CONSTRAINT fk_kassenbuch_header_shop_id__shop_id FOREIGN KEY (shop_id) REFERENCES public.shop_info(shop_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.kassenbuch_header
    ADD CONSTRAINT fk_kassenbuch_header_tse_transaction_id__id FOREIGN KEY (tse_transaction_id) REFERENCES public.tse_transaction(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.kassenbuch_header
    ADD CONSTRAINT fk_kassenbuch_header_zbon_id__zbon_id FOREIGN KEY (zbon_id) REFERENCES public.zbon(zbon_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.kassenbuch_line_item
    ADD CONSTRAINT fk_kassenbuch_line_item_header_id__id FOREIGN KEY (header_id) REFERENCES public.kassenbuch_header(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY public.opening_hours_override
    ADD CONSTRAINT fk_opening_hours_override_shop_id__shop_id FOREIGN KEY (shop_id) REFERENCES public.shop_info(shop_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.opening_hours
    ADD CONSTRAINT fk_opening_hours_shop_id__shop_id FOREIGN KEY (shop_id) REFERENCES public.shop_info(shop_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.payment
    ADD CONSTRAINT fk_payment_bill_id__id FOREIGN KEY (bill_id) REFERENCES public.bill(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.payment
    ADD CONSTRAINT fk_payment_payment_type_id__id FOREIGN KEY (payment_type_id) REFERENCES public.payment_type(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.payment
    ADD CONSTRAINT fk_payment_refunded_bill_id__id FOREIGN KEY (refunded_bill_id) REFERENCES public.bill(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.print_job_history
    ADD CONSTRAINT fk_print_job_history_printer_location_id__id FOREIGN KEY (printer_location_id) REFERENCES public.printer_location(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.printer_location
    ADD CONSTRAINT fk_printer_location_printer_setup_id__id FOREIGN KEY (printer_setup_id) REFERENCES public.printer_setup(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.product
    ADD CONSTRAINT fk_product_category_id__id FOREIGN KEY (category_id) REFERENCES public.product_category(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.product_category_printer_location
    ADD CONSTRAINT fk_product_category_printer_location_printer_location_id__id FOREIGN KEY (printer_location_id) REFERENCES public.printer_location(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.product_category_printer_location
    ADD CONSTRAINT fk_product_category_printer_location_product_category_id__id FOREIGN KEY (product_category_id) REFERENCES public.product_category(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.product_extras_assignment
    ADD CONSTRAINT fk_product_extras_assignment_extras_id__id FOREIGN KEY (extras_id) REFERENCES public.product_extras(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.product_extras_assignment
    ADD CONSTRAINT fk_product_extras_assignment_product_id__id FOREIGN KEY (product_id) REFERENCES public.product(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.promotion
    ADD CONSTRAINT fk_promotion_created_by__employee_id FOREIGN KEY (created_by) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.promotion
    ADD CONSTRAINT fk_promotion_target_product_id__id FOREIGN KEY (target_product_id) REFERENCES public.product(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.r_table
    ADD CONSTRAINT fk_r_table_current_table_session_id__table_session_id FOREIGN KEY (current_table_session_id) REFERENCES public.table_session(table_session_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.r_table
    ADD CONSTRAINT fk_r_table_location_type_id__id FOREIGN KEY (location_type_id) REFERENCES public.location_types(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.reservation_table_assignments
    ADD CONSTRAINT fk_reservation_table_assignments_assigned_by__employee_id FOREIGN KEY (assigned_by) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.reservation_table_assignments
    ADD CONSTRAINT fk_reservation_table_assignments_reservation_id__id FOREIGN KEY (reservation_id) REFERENCES public.reservations(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.reservation_table_assignments
    ADD CONSTRAINT fk_reservation_table_assignments_table_id__table_id FOREIGN KEY (table_id) REFERENCES public.r_table(table_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT fk_reservations_created_by__employee_id FOREIGN KEY (created_by) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT fk_reservations_customer_id__customer_id FOREIGN KEY (customer_id) REFERENCES public.customers(customer_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT fk_reservations_updated_by__employee_id FOREIGN KEY (updated_by) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.room_element
    ADD CONSTRAINT fk_room_element_location_type_id__id FOREIGN KEY (location_type_id) REFERENCES public.location_types(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.shop_configuration
    ADD CONSTRAINT fk_shop_configuration_shop_id__shop_id FOREIGN KEY (shop_id) REFERENCES public.shop_info(shop_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.t_order
    ADD CONSTRAINT fk_t_order_employee_id__employee_id FOREIGN KEY (employee_id) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.t_order_item
    ADD CONSTRAINT fk_t_order_item_bill_id__id FOREIGN KEY (bill_id) REFERENCES public.bill(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.t_order_item
    ADD CONSTRAINT fk_t_order_item_duplicated_from_bill_id__id FOREIGN KEY (duplicated_from_bill_id) REFERENCES public.bill(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.t_order_item
    ADD CONSTRAINT fk_t_order_item_orders_id__id FOREIGN KEY (orders_id) REFERENCES public.t_order(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.t_order_item
    ADD CONSTRAINT fk_t_order_item_product_id__id FOREIGN KEY (product_id) REFERENCES public.product(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.t_order_item
    ADD CONSTRAINT fk_t_order_item_table_session_id__table_session_id FOREIGN KEY (table_session_id) REFERENCES public.table_session(table_session_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.t_order_item
    ADD CONSTRAINT fk_t_order_item_vat_id__id FOREIGN KEY (vat_id) REFERENCES public.vat(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.t_order
    ADD CONSTRAINT fk_t_order_table_session_id__table_session_id FOREIGN KEY (table_session_id) REFERENCES public.table_session(table_session_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.t_order
    ADD CONSTRAINT fk_t_order_terminal_id__id FOREIGN KEY (terminal_id) REFERENCES public.terminals(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.t_order
    ADD CONSTRAINT fk_t_order_tse_transaction_id__id FOREIGN KEY (tse_transaction_id) REFERENCES public.tse_transaction(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.table_session
    ADD CONSTRAINT fk_table_session_customer_id__customer_id FOREIGN KEY (customer_id) REFERENCES public.customers(customer_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.table_session
    ADD CONSTRAINT fk_table_session_delivery_address_id__address_id FOREIGN KEY (delivery_address_id) REFERENCES public.customer_addresses(address_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.table_session
    ADD CONSTRAINT fk_table_session_employee_id__employee_id FOREIGN KEY (employee_id) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.table_session
    ADD CONSTRAINT fk_table_session_table_id__table_id FOREIGN KEY (table_id) REFERENCES public.r_table(table_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.table_session
    ADD CONSTRAINT fk_table_session_tse_transaction_id__id FOREIGN KEY (tse_transaction_id) REFERENCES public.tse_transaction(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.table_session
    ADD CONSTRAINT fk_table_session_zbon_id__zbon_id FOREIGN KEY (zbon_id) REFERENCES public.zbon(zbon_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.tax_group
    ADD CONSTRAINT fk_tax_group_vat_id__id FOREIGN KEY (vat_id) REFERENCES public.vat(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.tax_group
    ADD CONSTRAINT fk_tax_group_zbon_id__zbon_id FOREIGN KEY (zbon_id) REFERENCES public.zbon(zbon_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.terminals
    ADD CONSTRAINT fk_terminals_shop_info_id__shop_id FOREIGN KEY (shop_info_id) REFERENCES public.shop_info(shop_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.time_approval
    ADD CONSTRAINT fk_time_approval_approved_by__employee_id FOREIGN KEY (approved_by) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.time_approval
    ADD CONSTRAINT fk_time_approval_employee_id__employee_id FOREIGN KEY (employee_id) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.time_break
    ADD CONSTRAINT fk_time_break_time_entry_id__id FOREIGN KEY (time_entry_id) REFERENCES public.time_entry(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.time_entry
    ADD CONSTRAINT fk_time_entry_cost_center_id__id FOREIGN KEY (cost_center_id) REFERENCES public.cost_center(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.time_entry
    ADD CONSTRAINT fk_time_entry_employee_id__employee_id FOREIGN KEY (employee_id) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.tse_signing_queue
    ADD CONSTRAINT fk_tse_signing_queue_tse_transaction_id__id FOREIGN KEY (tse_transaction_id) REFERENCES public.tse_transaction(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.voucher
    ADD CONSTRAINT fk_voucher_order_item_id__id FOREIGN KEY (order_item_id) REFERENCES public.t_order_item(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.voucher_redeem
    ADD CONSTRAINT fk_voucher_redeem_payment_id__id FOREIGN KEY (payment_id) REFERENCES public.payment(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.voucher_redeem
    ADD CONSTRAINT fk_voucher_redeem_voucher_id__id FOREIGN KEY (voucher_id) REFERENCES public.voucher(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY public.voucher_redeem
    ADD CONSTRAINT fk_voucher_redeem_zbon_id__zbon_id FOREIGN KEY (zbon_id) REFERENCES public.zbon(zbon_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.voucher
    ADD CONSTRAINT fk_voucher_zbon_id__zbon_id FOREIGN KEY (zbon_id) REFERENCES public.zbon(zbon_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.work_schedules
    ADD CONSTRAINT fk_work_schedules_employee_id__employee_id FOREIGN KEY (employee_id) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.zbon
    ADD CONSTRAINT fk_zbon_employee_id__employee_id FOREIGN KEY (employee_id) REFERENCES public.employees(employee_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.zbon_payment_summary
    ADD CONSTRAINT fk_zbon_payment_summary_payment_type_name__id FOREIGN KEY (payment_type_name) REFERENCES public.payment_type(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.zbon_payment_summary
    ADD CONSTRAINT fk_zbon_payment_summary_zbon_id__zbon_id FOREIGN KEY (zbon_id) REFERENCES public.zbon(zbon_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY public.zbon
    ADD CONSTRAINT fk_zbon_shop_info_id__shop_id FOREIGN KEY (shop_info_id) REFERENCES public.shop_info(shop_id) ON UPDATE RESTRICT ON DELETE RESTRICT;

