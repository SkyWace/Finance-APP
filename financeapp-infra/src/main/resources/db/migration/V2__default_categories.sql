-- Categories par defaut. system_code permet de les reconnaitre de maniere stable
-- (futures regles de categorisation, migrations) meme si l'utilisateur les renomme.

INSERT INTO categories (name, kind, sort_order, system_code) VALUES ('Logement', 'EXPENSE', 0, 'HOUSING');
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Loyer', 'EXPENSE', 0, 'HOUSING.RENT' FROM categories WHERE system_code = 'HOUSING';
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Électricité', 'EXPENSE', 10, 'HOUSING.ELECTRICITY' FROM categories WHERE system_code = 'HOUSING';
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Gaz', 'EXPENSE', 20, 'HOUSING.GAS' FROM categories WHERE system_code = 'HOUSING';
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Eau', 'EXPENSE', 30, 'HOUSING.WATER' FROM categories WHERE system_code = 'HOUSING';
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Internet', 'EXPENSE', 40, 'HOUSING.INTERNET' FROM categories WHERE system_code = 'HOUSING';

INSERT INTO categories (name, kind, sort_order, system_code) VALUES ('Transport', 'EXPENSE', 10, 'TRANSPORT');
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Carburant', 'EXPENSE', 0, 'TRANSPORT.FUEL' FROM categories WHERE system_code = 'TRANSPORT';
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Assurance', 'EXPENSE', 10, 'TRANSPORT.INSURANCE' FROM categories WHERE system_code = 'TRANSPORT';
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Entretien', 'EXPENSE', 20, 'TRANSPORT.MAINTENANCE' FROM categories WHERE system_code = 'TRANSPORT';
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Péage', 'EXPENSE', 30, 'TRANSPORT.TOLL' FROM categories WHERE system_code = 'TRANSPORT';
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Parking', 'EXPENSE', 40, 'TRANSPORT.PARKING' FROM categories WHERE system_code = 'TRANSPORT';
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Crédit véhicule', 'EXPENSE', 50, 'TRANSPORT.CAR_LOAN' FROM categories WHERE system_code = 'TRANSPORT';

INSERT INTO categories (name, kind, sort_order, system_code) VALUES ('Alimentation', 'EXPENSE', 20, 'FOOD');
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Courses', 'EXPENSE', 0, 'FOOD.GROCERIES' FROM categories WHERE system_code = 'FOOD';
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Restaurant', 'EXPENSE', 10, 'FOOD.RESTAURANT' FROM categories WHERE system_code = 'FOOD';
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Fast-food', 'EXPENSE', 20, 'FOOD.FAST_FOOD' FROM categories WHERE system_code = 'FOOD';

INSERT INTO categories (name, kind, sort_order, system_code) VALUES ('Abonnements', 'EXPENSE', 30, 'SUBSCRIPTIONS');
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Streaming', 'EXPENSE', 0, 'SUBSCRIPTIONS.STREAMING' FROM categories WHERE system_code = 'SUBSCRIPTIONS';
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Musique', 'EXPENSE', 10, 'SUBSCRIPTIONS.MUSIC' FROM categories WHERE system_code = 'SUBSCRIPTIONS';
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Téléphone', 'EXPENSE', 20, 'SUBSCRIPTIONS.PHONE' FROM categories WHERE system_code = 'SUBSCRIPTIONS';
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Sport', 'EXPENSE', 30, 'SUBSCRIPTIONS.SPORT' FROM categories WHERE system_code = 'SUBSCRIPTIONS';

INSERT INTO categories (name, kind, sort_order, system_code) VALUES ('Loisirs', 'EXPENSE', 40, 'LEISURE');

INSERT INTO categories (name, kind, sort_order, system_code) VALUES ('Santé', 'EXPENSE', 50, 'HEALTH');

INSERT INTO categories (name, kind, sort_order, system_code) VALUES ('Shopping', 'EXPENSE', 60, 'SHOPPING');

INSERT INTO categories (name, kind, sort_order, system_code) VALUES ('Épargne', 'BOTH', 70, 'SAVINGS');

INSERT INTO categories (name, kind, sort_order, system_code) VALUES ('Revenus', 'INCOME', 80, 'INCOME');
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Salaire', 'INCOME', 0, 'INCOME.SALARY' FROM categories WHERE system_code = 'INCOME';
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Remboursement', 'INCOME', 10, 'INCOME.REFUND' FROM categories WHERE system_code = 'INCOME';
INSERT INTO categories (parent_id, name, kind, sort_order, system_code) SELECT id, 'Autres revenus', 'INCOME', 20, 'INCOME.OTHER_INCOME' FROM categories WHERE system_code = 'INCOME';

INSERT INTO categories (name, kind, sort_order, system_code) VALUES ('Autres', 'BOTH', 90, 'OTHER');
