"use client";

import { useId, useState, useEffect } from "react";
import { getTemplates, createTemplate, DEFAULT_TEMPLATES, type Template } from "@/lib/templates";
import { notifyDataChanged } from "@/lib/data-refresh";
import { useTranslation } from "@/lib/i18n";
import { Modal } from "@/components/ui/Modal";

interface TemplatePickerProps {
  open: boolean;
  onClose: () => void;
  onSelect: (content: string) => void;
  currentNote?: string;
}

export function TemplatePicker({ open, onClose, onSelect, currentNote }: TemplatePickerProps) {
  const { t } = useTranslation();
  const titleId = useId();
  const [templates, setTemplates] = useState<Template[]>([]);
  const [showSaveForm, setShowSaveForm] = useState(false);
  const [saveName, setSaveName] = useState("");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (open) {
      loadTemplates();
      setShowSaveForm(false);
      setSaveName("");
    }
  }, [open]);

  async function loadTemplates() {
    try {
      const data = await getTemplates();
      // If no templates exist, seed defaults
      if (data.length === 0) {
        for (const tpl of DEFAULT_TEMPLATES) {
          try { await createTemplate(tpl); } catch {}
        }
        const refreshed = await getTemplates();
        setTemplates(refreshed);
      } else {
        setTemplates(data);
      }
    } catch {}
  }

  async function handleSaveAsTemplate() {
    if (!saveName.trim() || !currentNote?.trim()) return;
    setSaving(true);
    try {
      await createTemplate({
        name: saveName.trim(),
        content: currentNote.trim(),
        sort_order: templates.length,
      });
      notifyDataChanged("templates");
      setShowSaveForm(false);
      setSaveName("");
      await loadTemplates();
    } catch {}
    setSaving(false);
  }

  if (!open) return null;

  return (
    <Modal
      onClose={onClose}
      labelledBy={titleId}
      panelClassName="relative glass-card p-6 w-full max-w-md mx-4 mb-4 sm:mb-0 max-h-[85vh] overflow-y-auto"
    >
      <div className="flex items-center justify-between mb-4">
        <h2 id={titleId} className="text-xl font-bold text-white">
          {t("templates.title")}
        </h2>
        <button
          onClick={onClose}
          aria-label={t("common.close")}
          className="text-white/40 hover:text-white transition-colors text-xl"
        >
          ✕
        </button>
      </div>

      {/* Save as template */}
      {currentNote?.trim() && (
        <div className="mb-4">
          {!showSaveForm ? (
            <button
              onClick={() => setShowSaveForm(true)}
              className="w-full py-2 rounded-xl bg-indigo-500/10 text-indigo-300 text-sm hover:bg-indigo-500/20 transition-colors"
            >
              💾 {t("templates.save_as_template")}
            </button>
          ) : (
            <div className="flex gap-2">
              <input
                type="text"
                value={saveName}
                onChange={(e) => setSaveName(e.target.value)}
                placeholder={t("templates.template_name")}
                className="flex-1 rounded-xl bg-white/10 border border-white/10 text-white px-3 py-2 text-sm focus:outline-none focus:border-indigo-400 placeholder:text-white/30"
              />
              <button
                onClick={handleSaveAsTemplate}
                disabled={saving || !saveName.trim()}
                className="px-3 py-2 rounded-xl bg-indigo-500 text-white text-sm font-medium hover:bg-indigo-600 disabled:opacity-50"
              >
                {saving ? "..." : t("common.save")}
              </button>
            </div>
          )}
        </div>
      )}

      {/* Template list */}
      {templates.length === 0 ? (
        <p className="text-white/40 text-sm text-center py-4">{t("templates.no_templates")}</p>
      ) : (
        <div className="space-y-2">
          {templates.map((tpl) => (
            <button
              key={tpl.id}
              onClick={() => {
                onSelect(tpl.content);
                onClose();
              }}
              className="w-full text-left p-3 rounded-xl bg-white/5 border border-white/10 hover:bg-white/10 hover:border-white/20 transition-all"
            >
              <div className="font-medium text-white text-sm mb-1">{tpl.name}</div>
              <div className="text-white/40 text-xs line-clamp-2 whitespace-pre-wrap">
                {tpl.content.substring(0, 120)}
              </div>
            </button>
          ))}
        </div>
      )}
    </Modal>
  );
}
