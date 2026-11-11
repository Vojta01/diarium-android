-- ============================================================
-- DIARIUM — DATABÁZOVÉ SCHÉMA
-- Spustit v Supabase SQL Editoru:
-- https://supabase.com/dashboard/project/YOUR_PROJECT_REF/sql/new
-- ============================================================

-- 1. PROFILES (napojené na auth.users)
CREATE TABLE IF NOT EXISTS profiles (
  id          UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  github_user TEXT,
  default_repo TEXT,
  created_at  TIMESTAMPTZ DEFAULT now(),
  updated_at  TIMESTAMPTZ DEFAULT now()
);

ALTER TABLE profiles ENABLE ROW LEVEL SECURITY;
CREATE POLICY profiles_own ON profiles FOR ALL USING (id = auth.uid());

-- Auto-create profile on signup
CREATE OR REPLACE FUNCTION handle_new_user()
RETURNS TRIGGER AS $$
BEGIN
  INSERT INTO public.profiles (id, github_user)
  VALUES (new.id, new.raw_user_meta_data->>'user_name');
  RETURN new;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
  AFTER INSERT ON auth.users
  FOR EACH ROW EXECUTE FUNCTION handle_new_user();

-- 2. ENTRIES — denní zápisy
CREATE TABLE IF NOT EXISTS entries (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  date             DATE NOT NULL,
  mood             SMALLINT CHECK (mood BETWEEN 1 AND 5),
  mood_emoji       TEXT,
  sleep_quality    SMALLINT CHECK (sleep_quality BETWEEN 1 AND 5),
  stress           SMALLINT CHECK (stress BETWEEN 1 AND 5),
  activities       JSONB DEFAULT '[]'::jsonb,
  habits           JSONB DEFAULT '{}'::jsonb,
  gratitude        JSONB DEFAULT '[]'::jsonb,
  note             TEXT DEFAULT '',
  weather          JSONB DEFAULT '[]'::jsonb,
  phone_screen_time INTEGER,
  phone_unlocks     INTEGER,
  phone_top_apps    JSONB DEFAULT '[]'::jsonb,
  photo_path       TEXT,
  created_at       TIMESTAMPTZ DEFAULT now(),
  updated_at       TIMESTAMPTZ DEFAULT now(),
  UNIQUE (user_id, date)
);

CREATE INDEX IF NOT EXISTS idx_entries_user_date ON entries (user_id, date DESC);
CREATE INDEX IF NOT EXISTS idx_entries_mood ON entries (user_id, mood);
CREATE INDEX IF NOT EXISTS idx_entries_activities ON entries USING GIN (activities);
CREATE INDEX IF NOT EXISTS idx_entries_habits ON entries USING GIN (habits);

ALTER TABLE entries ENABLE ROW LEVEL SECURITY;
CREATE POLICY entries_own ON entries FOR ALL USING (user_id = auth.uid());

-- 3. ACTIVITY CATALOG — výchozí aktivity
CREATE TABLE IF NOT EXISTS activity_catalog (
  id          SERIAL PRIMARY KEY,
  key         TEXT NOT NULL UNIQUE,
  label       TEXT NOT NULL,
  icon        TEXT DEFAULT '📌',
  category    TEXT DEFAULT 'obecné',
  color       TEXT DEFAULT '#6366f1',
  is_default  BOOLEAN DEFAULT true,
  sort_order  SMALLINT DEFAULT 0
);

INSERT INTO activity_catalog (key, label, icon, category, color, sort_order) VALUES
  -- Společenské
  ('rodina', 'Rodina', '👨‍👩‍👧', 'sociální', '#8b5cf6', 1),
  ('pratele', 'Přátelé', '👥', 'sociální', '#8b5cf6', 2),
  ('rande', 'Rande', '💑', 'sociální', '#ec4899', 3),
  ('party', 'Párty', '🎉', 'sociální', '#f97316', 4),
  ('office', 'Office', '🏢', 'sociální', '#3b82f6', 5),
  -- Záliby
  ('filmy_a_tv', 'Filmy a TV', '🎬', 'volný čas', '#eab308', 10),
  ('cteni', 'Čtení', '📖', 'volný čas', '#a855f7', 11),
  ('hrani_her', 'Hraní her', '🎮', 'volný čas', '#22c55e', 12),
  ('sport', 'Sport', '🏃', 'volný čas', '#22c55e', 13),
  ('relax', 'Relax', '😌', 'volný čas', '#8b5cf6', 14),
  ('hudba', 'Hudba', '🎵', 'volný čas', '#ef4444', 15),
  -- Jídlo
  ('jist_zdrave', 'Jíst zdravě', '🥗', 'jídlo', '#22c55e', 20),
  ('rychle_obcerstveni', 'Rychlé občerstvení', '🍔', 'jídlo', '#f97316', 21),
  ('domaci_vyroba', 'Domácí výroba', '🍳', 'jídlo', '#f59e0b', 22),
  ('restaurace', 'Restaurace', '🍽️', 'jídlo', '#f97316', 23),
  ('donaska', 'Donáška', '📦', 'jídlo', '#f97316', 24),
  ('den_bez_masa', 'Den bez masa', '🥬', 'jídlo', '#22c55e', 25),
  ('zadne_sladkosti', 'Žádné sladkosti', '🚫🍰', 'jídlo', '#ef4444', 26),
  ('zadne_limonady', 'Žádné limonády', '🚫🥤', 'jídlo', '#ef4444', 27),
  -- Zdraví / Sport
  ('trenink', 'Trénink', '🏋️', 'sport', '#16a34a', 30),
  ('pit_vody', 'Pít vodu', '💧', 'sport', '#3b82f6', 31),
  ('chuze', 'Chůze', '🚶', 'sport', '#84cc16', 32),
  ('kolo', 'Kolo', '🚴', 'sport', '#84cc16', 33),
  ('plavani', 'Plavání', '🏊', 'sport', '#06b6d4', 34),
  ('paddleboard', 'Paddleboard', '🏄', 'sport', '#06b6d4', 35),
  ('snooker', 'Snooker', '🎱', 'sport', '#ef4444', 36),
  -- Mé lepší já
  ('meditovat', 'Meditovat', '🧘', 'wellness', '#7c3aed', 40),
  ('laskavost', 'Laskavost', '💝', 'wellness', '#ec4899', 41),
  ('naslouchani', 'Naslouchání', '👂', 'wellness', '#8b5cf6', 42),
  ('darcovstvi', 'Dárcovství', '💰', 'wellness', '#22c55e', 43),
  ('dej_darek', 'Dej dárek', '🎁', 'wellness', '#f97316', 44),
  ('terapie', 'Terapie', '🛋️', 'wellness', '#8b5cf6', 45),
  ('integrita', 'Integrita', '⚖️', 'wellness', '#6366f1', 46),
  -- Domácí práce
  ('nakupovani', 'Nakupování', '🛒', 'domácí práce', '#f59e0b', 50),
  ('uklizeni', 'Uklízení', '🧹', 'domácí práce', '#3b82f6', 51),
  ('vareni', 'Vaření', '🍲', 'domácí práce', '#f97316', 52),
  ('prani', 'Praní', '🧺', 'domácí práce', '#3b82f6', 53),
  ('zehleni', 'Žehlení', '👕', 'domácí práce', '#ef4444', 54),
  -- Počasí
  ('slunecno', 'Slunečno', '☀️', 'počasí', '#eab308', 60),
  ('zatazeno', 'Zataženo', '☁️', 'počasí', '#9ca3af', 61),
  ('dest', 'Déšť', '🌧️', 'počasí', '#3b82f6', 62),
  ('snih', 'Sníh', '❄️', 'počasí', '#e0e7ff', 63),
  ('mraz', 'Mráz', '🥶', 'počasí', '#93c5fd', 64),
  ('horko', 'Horko', '🌡️', 'počasí', '#ef4444', 65),
  ('bourka', 'Bouřka', '🌩️', 'počasí', '#f59e0b', 66),
  ('vitr', 'Vítr', '💨', 'počasí', '#9ca3af', 67)
ON CONFLICT (key) DO UPDATE SET
  label = EXCLUDED.label, icon = EXCLUDED.icon,
  category = EXCLUDED.category, sort_order = EXCLUDED.sort_order;

-- 4. HABIT CATALOG — výchozí návyky
CREATE TABLE IF NOT EXISTS habit_catalog (
  id          SERIAL PRIMARY KEY,
  key         TEXT NOT NULL UNIQUE,
  label       TEXT NOT NULL,
  icon        TEXT DEFAULT '✅',
  category    TEXT DEFAULT 'obecné',
  color       TEXT DEFAULT '#6366f1',
  is_default  BOOLEAN DEFAULT true,
  is_negative BOOLEAN DEFAULT false,
  sort_order  SMALLINT DEFAULT 0
);

INSERT INTO habit_catalog (key, label, icon, category, is_negative, sort_order) VALUES
  ('alkohol', 'Alkohol', '🍺', 'zdraví', true, 1)
ON CONFLICT (key) DO NOTHING;

-- 5. USER_ACTIVITIES — vlastní aktivity
CREATE TABLE IF NOT EXISTS user_activities (
  id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id   UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  key       TEXT NOT NULL,
  label     TEXT NOT NULL,
  icon      TEXT DEFAULT '📌',
  category  TEXT DEFAULT 'vlastní',
  color     TEXT DEFAULT '#6366f1',
  is_active BOOLEAN DEFAULT true,
  created_at TIMESTAMPTZ DEFAULT now(),
  UNIQUE (user_id, key)
);

ALTER TABLE user_activities ENABLE ROW LEVEL SECURITY;
CREATE POLICY user_activities_own ON user_activities FOR ALL USING (user_id = auth.uid());

-- 6. USER_HABITS — vlastní návyky
CREATE TABLE IF NOT EXISTS user_habits (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  key         TEXT NOT NULL,
  label       TEXT NOT NULL,
  icon        TEXT DEFAULT '✅',
  category    TEXT DEFAULT 'vlastní',
  color       TEXT DEFAULT '#6366f1',
  is_negative BOOLEAN DEFAULT false,
  is_active   BOOLEAN DEFAULT true,
  created_at  TIMESTAMPTZ DEFAULT now(),
  UNIQUE (user_id, key)
);

ALTER TABLE user_habits ENABLE ROW LEVEL SECURITY;
CREATE POLICY user_habits_own ON user_habits FOR ALL USING (user_id = auth.uid());

-- HOTOVO ✓
