-- Clean up duplicate device records keeping the newest record per hardware_id or name
DELETE FROM devices d1
WHERE EXISTS (
    SELECT 1 FROM devices d2
    WHERE (
        (d1.hardware_id IS NOT NULL AND d1.hardware_id = d2.hardware_id) OR
        (d1.hardware_id IS NULL AND d1.name = d2.name)
    )
    AND (
        d2.created_at > d1.created_at OR
        (d2.created_at = d1.created_at AND d2.id > d1.id)
    )
);
