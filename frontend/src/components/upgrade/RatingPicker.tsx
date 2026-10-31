import React, { useId } from 'react';

/** The scale a progress rating is on, which the API's `rating` takes from 1 to this. */
export const RATING_MAX = 5;
const SCORES = Array.from({ length: RATING_MAX }, (_, i) => i + 1);

/**
 * @param legend   what the group is called, read before the scores
 * @param value    the score chosen, or none yet
 * @param onChange called with a score when one is chosen
 */
interface Props {
  legend: string;
  value: number | undefined;
  onChange: (score: number) => void;
}

/**
 * A 1-to-5 rating, as one radio group (WCAG 4.1.2, 1.4.1).
 *
 * Native radios rather than five buttons, for the reason `ThemeToggle` gives: the group, the arrow keys
 * and the "3 of 5, selected" announcement come with the element. Each is named "Rate n of 5", which
 * keeps the visible number in the name (2.5.3) and says what the scale is. The inputs are `sr-only`, and
 * the circle drawn for each is the peer that shows its state.
 *
 * The chosen score is told apart by a ring as well as by its fill, because the brand fill against the
 * unselected one is 2.53:1 in the dark theme, below the 3:1 a state needs (1.4.11), and colour alone
 * would not carry it anyway (1.4.1). The ring is `fg`, far above 3:1 on the surface in both themes.
 */
const RatingPicker: React.FC<Props> = ({ legend, value, onChange }) => {
  const name = useId();
  return (
    <fieldset>
      <legend className="text-sm font-medium text-fg-muted">{legend}</legend>
      <div className="flex gap-2 mt-2">
        {SCORES.map((score) => (
          <label key={score} className="cursor-pointer">
            <input
              type="radio"
              name={name}
              value={score}
              checked={value === score}
              onChange={() => onChange(score)}
              aria-label={`Rate ${score} of ${RATING_MAX}`}
              className="peer sr-only"
            />
            <span
              aria-hidden="true"
              className="flex w-10 h-10 items-center justify-center rounded-full border-2 border-transparent text-sm font-semibold transition-colors bg-muted text-fg-muted hover:bg-muted-strong peer-checked:bg-brand peer-checked:text-fg-inverted peer-checked:border-fg peer-focus-visible:ring-2 peer-focus-visible:ring-focus peer-focus-visible:ring-offset-2"
            >
              {score}
            </span>
          </label>
        ))}
      </div>
    </fieldset>
  );
};

export default RatingPicker;
