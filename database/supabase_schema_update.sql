-- Add ai_reflection column to entries
ALTER TABLE entries ADD COLUMN IF NOT EXISTS ai_reflection TEXT;

-- Create ai_reports table
CREATE TABLE IF NOT EXISTS ai_reports (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  type TEXT NOT NULL CHECK (type IN ('weekly', 'monthly', 'yearly')),
  period_start DATE NOT NULL,
  period_end DATE NOT NULL,
  content TEXT NOT NULL,
  created_at TIMESTAMPTZ DEFAULT now()
);

-- RLS for ai_reports
ALTER TABLE ai_reports ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can read own reports" ON ai_reports
  FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY "Users can insert own reports" ON ai_reports
  FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY "Service can manage all reports" ON ai_reports
  USING (true) WITH CHECK (true);

-- Index
CREATE INDEX IF NOT EXISTS idx_ai_reports_user_type ON ai_reports(user_id, type);
