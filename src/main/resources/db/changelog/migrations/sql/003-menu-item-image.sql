-- Фото блюда: ключ объекта в хранилище (MinIO), а не адрес.
--
-- Ключ вида «menu-items/12/<uuid>.png» не зависит от того, откуда фото отдаётся: адрес
-- собирается при чтении, и переезд хранилища или CDN не потребует трогать данные.
-- NULL — у блюда нет фото (так у всех блюд до этой миграции).
ALTER TABLE menu_item ADD COLUMN IF NOT EXISTS image_key VARCHAR(200);

COMMENT ON COLUMN menu_item.image_key IS 'Ключ фото блюда в хранилище; NULL — фото нет';
