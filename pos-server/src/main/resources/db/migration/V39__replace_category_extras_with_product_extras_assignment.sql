-- V39__replace_category_extras_with_product_extras_assignment.sql
-- Replace category-level extras with product-level extras assignment

CREATE TABLE product_extras_assignment (
    product_id BIGINT NOT NULL,
    extras_id INTEGER NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (product_id, extras_id),
    FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE CASCADE,
    FOREIGN KEY (extras_id) REFERENCES product_extras(id) ON DELETE CASCADE
);

INSERT INTO product_extras_assignment (product_id, extras_id, updated_at)
SELECT p.id, ce.product_extras_id, ce.updated_at
FROM product_category_product_extras ce
JOIN product p ON p.category_id = ce.product_category_id
ON CONFLICT DO NOTHING;

DROP TABLE product_category_product_extras;
