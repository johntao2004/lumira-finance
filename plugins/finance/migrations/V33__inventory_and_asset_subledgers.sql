-- PostgreSQL 18 DDL Candidate: Inventory & Long-term Asset Extensions (W08: INV01, INV02, INV03, AS01/AS02/AS03)

CREATE TABLE IF NOT EXISTS plugin_finance_inventory_warehouse (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid NOT NULL,
    code text NOT NULL CHECK(length(code) <= 64),
    name text NOT NULL CHECK(length(name) <= 200),
    location text,
    is_active boolean NOT NULL DEFAULT true,
    PRIMARY KEY(tenant_id, book_id, id),
    UNIQUE(tenant_id, book_id, code)
);

CREATE TABLE IF NOT EXISTS plugin_finance_inventory_item (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid NOT NULL,
    code text NOT NULL CHECK(length(code) <= 64),
    name text NOT NULL CHECK(length(name) <= 200),
    spec text,
    unit text NOT NULL CHECK(length(unit) <= 32),
    valuation_method text NOT NULL CHECK(valuation_method IN ('FIFO','MOVING_WEIGHTED_AVERAGE','MONTH_END_WEIGHTED_AVERAGE','SPECIFIC_BATCH')),
    inventory_account_id uuid NOT NULL,
    cogs_account_id uuid NOT NULL,
    loss_gain_account_id uuid NOT NULL,
    impairment_account_id uuid NOT NULL,
    impairment_expense_account_id uuid NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    PRIMARY KEY(tenant_id, book_id, id),
    UNIQUE(tenant_id, book_id, code)
);

CREATE TABLE IF NOT EXISTS plugin_finance_inventory_batch (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid NOT NULL,
    item_id uuid NOT NULL,
    batch_no text NOT NULL CHECK(length(batch_no) <= 128),
    inbound_date date NOT NULL,
    initial_cost numeric(28,8) NOT NULL CHECK(initial_cost >= 0),
    remaining_quantity numeric(28,8) NOT NULL CHECK(remaining_quantity >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id, book_id, id),
    UNIQUE(tenant_id, book_id, item_id, batch_no),
    FOREIGN KEY(tenant_id, book_id, item_id) REFERENCES plugin_finance_inventory_item(tenant_id, book_id, id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_inventory_movement (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    id uuid NOT NULL,
    movement_no text NOT NULL CHECK(length(movement_no) <= 128),
    movement_type text NOT NULL CHECK(movement_type IN ('PURCHASE_IN','PRODUCTION_ISSUE','PRODUCTION_IN','SALES_OUT','TRANSFER')),
    item_id uuid NOT NULL,
    source_warehouse_id uuid,
    dest_warehouse_id uuid,
    batch_id uuid,
    quantity numeric(28,8) NOT NULL CHECK(quantity > 0),
    unit_cost numeric(28,8) NOT NULL CHECK(unit_cost >= 0),
    total_cost numeric(28,8) NOT NULL CHECK(total_cost >= 0),
    source_evidence_id uuid,
    business_date date NOT NULL,
    status text NOT NULL CHECK(status IN ('DRAFT','SUBMITTED','APPROVED','POSTED','CANCELLED')),
    journal_id uuid,
    created_by uuid NOT NULL,
    approved_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id, book_id, id),
    UNIQUE(tenant_id, book_id, movement_no),
    FOREIGN KEY(tenant_id, book_id, item_id) REFERENCES plugin_finance_inventory_item(tenant_id, book_id, id),
    CHECK(movement_type <> 'TRANSFER' OR (source_warehouse_id IS NOT NULL AND dest_warehouse_id IS NOT NULL AND source_warehouse_id <> dest_warehouse_id)),
    CHECK(movement_type <> 'PURCHASE_IN' OR source_evidence_id IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS plugin_finance_inventory_stocktake (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid NOT NULL,
    sheet_no text NOT NULL CHECK(length(sheet_no) <= 128),
    warehouse_id uuid NOT NULL,
    cutoff_date date NOT NULL,
    status text NOT NULL CHECK(status IN ('DRAFT','COUNTED','APPROVED','POSTED','CANCELLED')),
    maker_id uuid NOT NULL,
    approver_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id, book_id, id),
    UNIQUE(tenant_id, book_id, sheet_no),
    FOREIGN KEY(tenant_id, book_id, warehouse_id) REFERENCES plugin_finance_inventory_warehouse(tenant_id, book_id, id),
    CHECK(status NOT IN ('APPROVED','POSTED') OR approver_id IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS plugin_finance_inventory_stocktake_line (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    stocktake_id uuid NOT NULL,
    id uuid NOT NULL,
    item_id uuid NOT NULL,
    batch_id uuid,
    book_quantity numeric(28,8) NOT NULL CHECK(book_quantity >= 0),
    book_unit_cost numeric(28,8) NOT NULL CHECK(book_unit_cost >= 0),
    counted_quantity numeric(28,8) NOT NULL CHECK(counted_quantity >= 0),
    variance_quantity numeric(28,8) NOT NULL,
    variance_amount numeric(28,8) NOT NULL,
    variance_type text NOT NULL CHECK(variance_type IN ('SURPLUS','DEFICIT','EQUAL')),
    disposition text NOT NULL CHECK(disposition IN ('MANAGEMENT_EXPENSE','NON_OPERATING_INCOME','NON_OPERATING_LOSS','PENDING_INVESTIGATION')),
    PRIMARY KEY(tenant_id, book_id, id),
    FOREIGN KEY(tenant_id, book_id, stocktake_id) REFERENCES plugin_finance_inventory_stocktake(tenant_id, book_id, id),
    FOREIGN KEY(tenant_id, book_id, item_id) REFERENCES plugin_finance_inventory_item(tenant_id, book_id, id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_inventory_impairment (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid NOT NULL,
    item_id uuid NOT NULL,
    regime text NOT NULL CHECK(regime IN ('CAS','SMALL')),
    period text NOT NULL CHECK(period ~ '^[0-9]{4}-(?:0[1-9]|1[0-2])$'),
    book_cost numeric(28,8) NOT NULL CHECK(book_cost >= 0),
    nrv numeric(28,8) NOT NULL CHECK(nrv >= 0),
    prior_impairment numeric(28,8) NOT NULL CHECK(prior_impairment >= 0),
    impairment_adjustment numeric(28,8) NOT NULL,
    new_impairment numeric(28,8) NOT NULL CHECK(new_impairment >= 0),
    status text NOT NULL CHECK(status IN ('PROVISIONED','REVERSED','BLOCKED')),
    journal_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id, book_id, id),
    UNIQUE(tenant_id, book_id, item_id, period),
    CHECK(regime <> 'SMALL' OR status = 'BLOCKED')
);

CREATE TABLE IF NOT EXISTS plugin_finance_asset_cip_project (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid NOT NULL,
    project_code text NOT NULL CHECK(length(project_code) <= 64),
    project_name text NOT NULL CHECK(length(project_name) <= 200),
    accumulated_cost numeric(28,8) NOT NULL CHECK(accumulated_cost >= 0),
    status text NOT NULL CHECK(status IN ('UNDER_CONSTRUCTION','READY_FOR_USE','CAPITALIZED','CANCELLED')),
    PRIMARY KEY(tenant_id, book_id, id),
    UNIQUE(tenant_id, book_id, project_code)
);

CREATE TABLE IF NOT EXISTS plugin_finance_asset_cip_capitalization (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    capitalized_cost numeric(28,8) NOT NULL CHECK(capitalized_cost > 0),
    cards_count int NOT NULL CHECK(cards_count >= 1),
    cards_payload jsonb NOT NULL,
    maker_id uuid NOT NULL,
    approver_id uuid NOT NULL,
    status text NOT NULL CHECK(status IN ('APPROVED','POSTED','CANCELLED')),
    journal_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id, book_id, id),
    FOREIGN KEY(tenant_id, book_id, project_id) REFERENCES plugin_finance_asset_cip_project(tenant_id, book_id, id),
    CHECK(maker_id <> approver_id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_asset_rd_project (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid NOT NULL,
    project_code text NOT NULL CHECK(length(project_code) <= 64),
    project_name text NOT NULL CHECK(length(project_name) <= 200),
    stage text NOT NULL CHECK(stage IN ('RESEARCH','DEVELOPMENT')),
    expensed_amount numeric(28,8) NOT NULL CHECK(expensed_amount >= 0),
    capitalized_amount numeric(28,8) NOT NULL CHECK(capitalized_amount >= 0),
    status text NOT NULL CHECK(status IN ('ONGOING','QUALIFIED_CAPITALIZED','CLOSED')),
    PRIMARY KEY(tenant_id, book_id, id),
    UNIQUE(tenant_id, book_id, project_code)
);

CREATE TABLE IF NOT EXISTS plugin_finance_asset_rd_capitalization (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    intangible_asset_name text NOT NULL CHECK(length(intangible_asset_name) <= 200),
    capitalized_amount numeric(28,8) NOT NULL CHECK(capitalized_amount > 0),
    source_evidence_id uuid NOT NULL,
    maker_id uuid NOT NULL,
    approver_id uuid NOT NULL,
    status text NOT NULL CHECK(status IN ('APPROVED','POSTED','CANCELLED')),
    journal_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id, book_id, id),
    FOREIGN KEY(tenant_id, book_id, project_id) REFERENCES plugin_finance_asset_rd_project(tenant_id, book_id, id),
    CHECK(maker_id <> approver_id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_asset_impairment (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid NOT NULL,
    asset_card_id uuid NOT NULL,
    regime text NOT NULL CHECK(regime IN ('CAS','SMALL')),
    period text NOT NULL CHECK(period ~ '^[0-9]{4}-(?:0[1-9]|1[0-2])$'),
    carrying_amount numeric(28,8) NOT NULL CHECK(carrying_amount >= 0),
    recoverable_amount numeric(28,8) NOT NULL CHECK(recoverable_amount >= 0),
    impairment_amount numeric(28,8) NOT NULL CHECK(impairment_amount >= 0),
    is_reversible boolean NOT NULL DEFAULT false CHECK(is_reversible = false),
    status text NOT NULL CHECK(status IN ('POSTED','BLOCKED')),
    journal_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id, book_id, id),
    UNIQUE(tenant_id, book_id, asset_card_id, period),
    CHECK(regime <> 'SMALL' OR status = 'BLOCKED')
);

CREATE TABLE IF NOT EXISTS plugin_finance_asset_stocktake (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid NOT NULL,
    sheet_no text NOT NULL CHECK(length(sheet_no) <= 128),
    stocktake_date date NOT NULL,
    status text NOT NULL CHECK(status IN ('OPEN','COUNTED','APPROVED','CLOSED')),
    maker_id uuid NOT NULL,
    approver_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id, book_id, id),
    UNIQUE(tenant_id, book_id, sheet_no),
    CHECK(status NOT IN ('APPROVED','CLOSED') OR approver_id IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS plugin_finance_asset_stocktake_line (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    stocktake_id uuid NOT NULL,
    id uuid NOT NULL,
    asset_card_id uuid NOT NULL,
    tag_rfid text NOT NULL CHECK(length(tag_rfid) <= 128),
    book_location text NOT NULL,
    actual_location text NOT NULL,
    variance_type text NOT NULL CHECK(variance_type IN ('NORMAL','SURPLUS','DEFICIT','LOCATION_MISMATCH')),
    disposition text NOT NULL CHECK(disposition IN ('UPDATE_LOCATION','DISPOSAL_LOSS','PROCEED_SURPLUS','NONE')),
    PRIMARY KEY(tenant_id, book_id, id),
    FOREIGN KEY(tenant_id, book_id, stocktake_id) REFERENCES plugin_finance_asset_stocktake(tenant_id, book_id, id)
);

-- Immutable Triggers
CREATE OR REPLACE FUNCTION plugin_finance_inventory_movement_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'INVENTORY_MOVEMENT_IMMUTABLE';
    END IF;
    IF OLD.status = 'POSTED' THEN
        RAISE EXCEPTION 'INVENTORY_MOVEMENT_POSTED_IMMUTABLE';
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_inventory_movement_guard ON plugin_finance_inventory_movement;
CREATE TRIGGER trg_inventory_movement_guard
    BEFORE UPDATE OR DELETE ON plugin_finance_inventory_movement
    FOR EACH ROW EXECUTE FUNCTION plugin_finance_inventory_movement_guard();

CREATE OR REPLACE FUNCTION plugin_finance_asset_impairment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ASSET_IMPAIRMENT_IMMUTABLE';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'POSTED' THEN
        RAISE EXCEPTION 'ASSET_IMPAIRMENT_POSTED_IMMUTABLE';
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_asset_impairment_guard ON plugin_finance_asset_impairment;
CREATE TRIGGER trg_asset_impairment_guard
    BEFORE UPDATE OR DELETE ON plugin_finance_asset_impairment
    FOR EACH ROW EXECUTE FUNCTION plugin_finance_asset_impairment_guard();
