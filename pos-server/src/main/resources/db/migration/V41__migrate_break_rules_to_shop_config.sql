-- Migrate break_rule table data into shop_configuration as JSON config

-- Insert break rules config for each shop, aggregating existing rules
INSERT INTO shop_configuration (shop_id, key, value, description, updated_at)
SELECT
    si.shop_id,
    'break_rules_config',
    jsonb_build_object(
        'rules',
        COALESCE(
            (SELECT jsonb_agg(
                jsonb_build_object(
                    'thresholdMinutes', br.threshold_minutes,
                    'breakMinutes', br.break_minutes
                ) ORDER BY br.threshold_minutes
            )
            FROM break_rule br
            WHERE br.is_active = true),
            '[]'::jsonb
        )
    ),
    'Break rules configuration: threshold-based mandatory break durations per ArbZG §4',
    NOW()
FROM shop_info si
ON CONFLICT (shop_id, key) DO UPDATE
SET value = EXCLUDED.value, updated_at = NOW();

-- Drop the old break_rule table
DROP TABLE IF EXISTS break_rule;
