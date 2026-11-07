-- V65__add_kassen_meldung_config.sql
-- Generate unique Kassen-Seriennummer (ZBP-XXXXXXXX) for each existing shop
-- and insert KASSEN_MELDUNG_CONFIG into shop_configuration.

-- Helper function to generate serial number from safe alphabet
CREATE OR REPLACE FUNCTION generate_kasse_serial() RETURNS TEXT AS $$
DECLARE
    chars TEXT := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    result TEXT := 'ZBP-';
    i INTEGER;
BEGIN
    FOR i IN 1..8 LOOP
        result := result || substr(chars, floor(random() * 32 + 1)::integer, 1);
    END LOOP;
    RETURN result;
END;
$$ LANGUAGE plpgsql;

-- Insert KASSEN_MELDUNG_CONFIG for each existing shop
-- Inbetriebnahme = date of the first order (actual system usage), falling back to shop created_at
INSERT INTO shop_configuration (shop_id, key, value, description, updated_at)
SELECT
    si.shop_id,
    'kassen_meldung_config',
    jsonb_build_object(
        'kasseSeriennr', generate_kasse_serial(),
        'kasseBrand', 'Zenbon POS',
        'kasseModell', 'Desktop/Server POS',
        'kasseSwBrand', 'Zenbon POS',
        'kasseSwVersion', '1.0.2',
        'artDesAufzeichnungssystems', 'Computergestützte/PC-Kassensysteme',
        'inbetriebnahme', to_char(
            COALESCE(
                (SELECT MIN(o.order_time) FROM t_order o),
                si.create_at
            ), 'YYYY-MM-DD')
    ),
    'Kassenmeldung configuration: serial number, brand, model, software info for ELSTER/DSFinV-K compliance',
    NOW()
FROM shop_info si
WHERE NOT EXISTS (
    SELECT 1 FROM shop_configuration sc
    WHERE sc.shop_id = si.shop_id AND sc.key = 'kassen_meldung_config'
);

-- Clean up helper function
DROP FUNCTION IF EXISTS generate_kasse_serial();
