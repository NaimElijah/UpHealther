import React, { useEffect } from 'react';

/**
 * @param isOpen   whether the dialog is shown; when false the component renders nothing at all
 * @param onClose  called by the close button, the backdrop and the Escape key alike
 * @param title    heading text
 * @param children the dialog body, usually a form
 */
interface ModalProps {
  isOpen: boolean;
  onClose: () => void;
  title: string;
  children: React.ReactNode;
}

/**
 * Centred dialog over a dimmed backdrop.
 *
 * Closes on Escape as well as on the button and the backdrop; the key listener is bound only while
 * open, so a closed modal costs nothing and several on one page cannot fight over the key. Nothing is
 * rendered when closed, which means the body is unmounted and its form state resets between openings.
 */
const Modal: React.FC<ModalProps> = ({ isOpen, onClose, title, children }) => {
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    if (isOpen) document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="absolute inset-0 bg-black/40" onClick={onClose} />
      <div className="relative z-10 w-full max-w-lg rounded-xl bg-white shadow-xl mx-4">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200">
          <h2 className="text-lg font-semibold text-gray-800">{title}</h2>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 text-2xl leading-none"
            aria-label="Close modal"
          >
            &times;
          </button>
        </div>
        <div className="px-6 py-4">{children}</div>
      </div>
    </div>
  );
};

export default Modal;
