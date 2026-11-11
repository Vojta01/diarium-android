-- ============================================================
-- DIARIUM — STORAGE MIGRACE: FOTKY
-- Spustit v Supabase SQL Editoru:
-- https://supabase.com/dashboard/project/YOUR_PROJECT_REF/sql/new
-- ============================================================

-- Vytvoř bucket pro fotky
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
  'diary-photos',
  'diary-photos',
  true,                          -- veřejný bucket (URL jsou přístupné)
  5242880,                       -- max 5 MB na soubor
  ARRAY['image/jpeg', 'image/png', 'image/webp', 'image/gif']
)
ON CONFLICT (id) DO UPDATE SET
  public = true,
  file_size_limit = 5242880,
  allowed_mime_types = ARRAY['image/jpeg', 'image/png', 'image/webp', 'image/gif'];

-- RLS politika: uživatel může číst jen svoje fotky
-- Struktura cesty: {user_id}/{date}/{filename}.jpg
DROP POLICY IF EXISTS "Users can view own photos" ON storage.objects;
CREATE POLICY "Users can view own photos"
ON storage.objects FOR SELECT
USING (
  bucket_id = 'diary-photos'
  AND auth.uid()::text = (storage.foldername(name))[1]
);

-- RLS politika: uživatel může nahrávat jen do své složky
DROP POLICY IF EXISTS "Users can upload own photos" ON storage.objects;
CREATE POLICY "Users can upload own photos"
ON storage.objects FOR INSERT
WITH CHECK (
  bucket_id = 'diary-photos'
  AND auth.uid()::text = (storage.foldername(name))[1]
);

-- RLS politika: uživatel může mazat jen svoje fotky
DROP POLICY IF EXISTS "Users can delete own photos" ON storage.objects;
CREATE POLICY "Users can delete own photos"
ON storage.objects FOR DELETE
USING (
  bucket_id = 'diary-photos'
  AND auth.uid()::text = (storage.foldername(name))[1]
);

-- RLS politika: uživatel může updatovat svoje fotky
DROP POLICY IF EXISTS "Users can update own photos" ON storage.objects;
CREATE POLICY "Users can update own photos"
ON storage.objects FOR UPDATE
WITH CHECK (
  bucket_id = 'diary-photos'
  AND auth.uid()::text = (storage.foldername(name))[1]
);
