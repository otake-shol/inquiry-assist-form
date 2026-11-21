import { PROGRESS_STEPS } from "../text/labels.ts";

/** 進行表示の props。 */
interface StepperProps {
  /** 現在の段階の位置（PROGRESS_STEPS の添字） */
  current: number;
}

/**
 * 現在の段階が分かる進行表示。
 * @param props 現在位置
 */
export function Stepper({ current }: StepperProps) {
  return (
    <nav aria-label="進行状況">
      <ol className="stepper">
        {PROGRESS_STEPS.map((label, index) => (
          <li
            key={label}
            className={
              index === current ? "current" : index < current ? "done" : ""
            }
            aria-current={index === current ? "step" : undefined}
          >
            <span className="stepper-no">{index + 1}</span>
            {label}
          </li>
        ))}
      </ol>
    </nav>
  );
}
