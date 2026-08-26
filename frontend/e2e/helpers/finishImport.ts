import { expect, type Page } from "@playwright/test";
import { sv } from "../../src/i18n/sv";

/** Row-count outcome a fixture is expected to produce, for {@link finishImportAfterUpload}'s
 *  optional `expectedRows` assertion. */
export interface ExpectedImportRows {
  ok: number;
  warn: number;
  skip: number;
}

/**
 * Completes import after a file has been uploaded. Prefers the one-click review screen when
 * auto-analysis is confident; otherwise walks the classic wizard steps.
 *
 * `expectedRows`, when given, asserts the row-count summary line shown just before committing -
 * `sv.importWizard.review.rowsSummary` on the one-click path (player/warning/skip counts from
 * auto-analysis) or `sv.importWizard.validate.summary` on the classic wizard path (ok/warn/skip from
 * the validate step) - so a fixture drifting out of sync with a spec's assumed row outcome fails
 * loudly here instead of silently importing the wrong number of participants.
 */
export async function finishImportAfterUpload(page: Page, expectedRows?: ExpectedImportRows): Promise<void> {
  const reviewHeading = page.getByRole("heading", { name: sv.importWizard.review.heading, level: 4 });
  const sheetHeading = page.getByRole("heading", { name: sv.importWizard.sheet.heading, level: 4 });

  await expect(reviewHeading.or(sheetHeading)).toBeVisible();

  if (await reviewHeading.isVisible()) {
    if (expectedRows) {
      await expect(
        page.getByText(
          sv.importWizard.review.rowsSummary(expectedRows.ok, expectedRows.warn, expectedRows.skip),
        ),
      ).toBeVisible();
    }
    await page.getByRole("button", { name: sv.importWizard.review.importButton, exact: true }).click();
  } else {
    await page.getByRole("button", { name: sv.importWizard.sheet.nextButton }).click();
    await expect(page.getByRole("heading", { name: sv.importWizard.mapping.heading, level: 4 })).toBeVisible();
    await page.getByRole("button", { name: sv.importWizard.mapping.nextButton }).click();
    await expect(page.getByRole("heading", { name: sv.importWizard.validate.heading, level: 4 })).toBeVisible();
    if (expectedRows) {
      await expect(
        page.getByText(sv.importWizard.validate.summary(expectedRows.ok, expectedRows.warn, expectedRows.skip)),
      ).toBeVisible();
    }
    await page.getByRole("button", { name: sv.importWizard.validate.nextButton }).click();
    await expect(page.getByRole("heading", { name: sv.importWizard.commit.heading, level: 4 })).toBeVisible();
    await page.getByRole("button", { name: sv.importWizard.commit.submit, exact: true }).click();
  }

  await expect(page.getByRole("heading", { name: sv.importWizard.commit.resultHeading, level: 4 })).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.commit.goToParticipants }).click();
  await expect(page).toHaveURL(/\/deltagare$/);
}
