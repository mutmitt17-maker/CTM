-- Test data for a hosted Supabase test project. Run once in the SQL editor. NOT a migration.
insert into agency (name, region) values ('Agence Test', 'Lubumbashi') on conflict (name) do nothing;

insert into inventory_item (imei, model, brand, color, purchase_price, retail_price, agency_id)
select v.imei, v.model, v.brand, v.color, v.purchase_price, v.retail_price, a.id
from agency a,
  (values
    ('356923100000010', 'Spark 20',  'Tecno',   'Noir',  70.00, 100.00),
    ('356923100000028', 'Hot 40i',   'Infinix', 'Bleu',  85.00, 120.00),
    ('356923100000036', 'Galaxy A05','Samsung', 'Argent',90.00, 130.00)
  ) as v(imei, model, brand, color, purchase_price, retail_price)
where a.name = 'Agence Test'
on conflict (imei) do nothing;
