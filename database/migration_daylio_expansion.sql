-- ============================================================
-- DIARIUM — DAYLIO FEATURE EXPANSION
-- Phase 1: New tables for goals, scales, templates, achievements
-- Run in Supabase SQL Editor
-- ============================================================

-- 1. GOALS — cíle vázané na aktivity
CREATE TABLE IF NOT EXISTS goals (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  activity_key TEXT NOT NULL,
  name TEXT NOT NULL,
  target_count INTEGER NOT NULL DEFAULT 1,
  frequency TEXT NOT NULL DEFAULT 'weekly' CHECK (frequency IN ('daily', 'weekly', 'monthly')),
  is_active BOOLEAN DEFAULT true,
  created_at TIMESTAMPTZ DEFAULT now(),
  archived_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ
);

ALTER TABLE goals ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users manage own goals" ON goals
  FOR ALL USING (auth.uid() = user_id);

CREATE INDEX IF NOT EXISTS idx_goals_user_active ON goals(user_id, is_active);

-- 2. SCALES — vlastní numerické škály (Energy, Pain, atd.)
CREATE TABLE IF NOT EXISTS scales (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  emoji TEXT NOT NULL DEFAULT '📊',
  min_value INTEGER DEFAULT 1,
  max_value INTEGER DEFAULT 10,
  unit TEXT,
  color TEXT DEFAULT '#6366f1',
  sort_order INTEGER DEFAULT 0,
  is_active BOOLEAN DEFAULT true,
  created_at TIMESTAMPTZ DEFAULT now()
);

ALTER TABLE scales ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users manage own scales" ON scales
  FOR ALL USING (auth.uid() = user_id);

-- 3. SCALE_ENTRIES — hodnoty škál za konkrétní den
CREATE TABLE IF NOT EXISTS scale_entries (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  scale_id UUID NOT NULL REFERENCES scales(id) ON DELETE CASCADE,
  date DATE NOT NULL,
  value INTEGER NOT NULL,
  created_at TIMESTAMPTZ DEFAULT now(),
  UNIQUE(user_id, scale_id, date)
);

ALTER TABLE scale_entries ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users manage own scale_entries" ON scale_entries
  FOR ALL USING (auth.uid() = user_id);

CREATE INDEX IF NOT EXISTS idx_scale_entries_date ON scale_entries(user_id, date);

-- 4. TEMPLATES — šablony pro poznámky
CREATE TABLE IF NOT EXISTS templates (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  content TEXT NOT NULL,
  sort_order INTEGER DEFAULT 0,
  created_at TIMESTAMPTZ DEFAULT now()
);

ALTER TABLE templates ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users manage own templates" ON templates
  FOR ALL USING (auth.uid() = user_id);

-- 5. ACHIEVEMENTS — gamifikace
CREATE TABLE IF NOT EXISTS achievements (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  achievement_key TEXT NOT NULL,
  unlocked_at TIMESTAMPTZ DEFAULT now(),
  progress INTEGER DEFAULT 0,
  target INTEGER DEFAULT 1,
  UNIQUE(user_id, achievement_key)
);

ALTER TABLE achievements ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users manage own achievements" ON achievements
  FOR ALL USING (auth.uid() = user_id);

-- 6. Extend entries table with scale_values (JSONB for flexibility)
ALTER TABLE entries ADD COLUMN IF NOT EXISTS scale_values JSONB DEFAULT '{}'::jsonb;

-- HOTOVO ✓
