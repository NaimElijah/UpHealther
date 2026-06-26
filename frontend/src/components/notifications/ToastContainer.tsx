import React from 'react';
import { useNavigate } from 'react-router-dom';
import type { AppNotification } from '../../types';
import { categoryMeta } from './notificationMeta';

export interface ToastData extends Pick<AppNotification, 'category' | 'title' | 'message' | 'relatedUpgradeId'> {
  id: string;
}

interface Props {
  toasts: ToastData[];
  onDismiss: (id: string) => void;
}

/** Transient real-time toasts, stacked top-right above everything. */
const ToastContainer: React.FC<Props> = ({ toasts, onDismiss }) => {
  const navigate = useNavigate();
  if (toasts.length === 0) return null;

  return (
    <div className="fixed top-4 right-4 z-[60] flex flex-col gap-2 w-80 max-w-[calc(100vw-2rem)]">
      {toasts.map((t) => {
        const meta = categoryMeta[t.category];
        return (
          // The card is a non-interactive live region; the interactive parts are real buttons so the
          // toast is keyboard- and screen-reader-accessible.
          <div
            key={t.id}
            role="status"
            aria-live="polite"
            className={`${meta.bg} ${meta.ring} border rounded-xl shadow-lg p-3 flex items-start gap-2 animate-[fadeIn_0.2s_ease-out]`}
          >
            <button
              type="button"
              onClick={() => {
                if (t.relatedUpgradeId) navigate(`/upgrades/${t.relatedUpgradeId}`);
                onDismiss(t.id);
              }}
              className="flex items-start gap-3 flex-1 min-w-0 text-left"
            >
              <span className="text-lg leading-none mt-0.5">{meta.icon}</span>
              <span className="flex-1 min-w-0">
                <span className={`block text-sm font-semibold ${meta.text} truncate`}>{t.title}</span>
                {t.message && <span className="block text-xs text-gray-600 mt-0.5 line-clamp-2">{t.message}</span>}
              </span>
            </button>
            <button
              type="button"
              onClick={() => onDismiss(t.id)}
              className="text-gray-400 hover:text-gray-600 text-lg leading-none shrink-0"
              aria-label="Dismiss notification"
            >
              &times;
            </button>
          </div>
        );
      })}
    </div>
  );
};

export default ToastContainer;
