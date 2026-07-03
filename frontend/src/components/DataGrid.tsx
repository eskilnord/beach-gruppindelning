import { AgGridReact } from "ag-grid-react";
import type { AgGridReactProps } from "ag-grid-react";
import { AllCommunityModule, ModuleRegistry, themeQuartz } from "ag-grid-community";
import type { ColDef, RowClickedEvent } from "ag-grid-community";
import { Box } from "@mantine/core";
import { sv } from "../i18n/sv";

// Registered once at module load — every AG Grid instance in the app shares the Community module
// set (no Enterprise features are used, see docs/plan.md solver-design "Enterprise boundary").
ModuleRegistry.registerModules([AllCommunityModule]);

/**
 * Sober Quartz theme tuned to match the app's ocean-blue primary color (app/theme.ts, v0.3.0 WI-6)
 * rather than AG Grid's default purple accent, so the grid doesn't look like a foreign widget dropped
 * into the app (M4 brief: "quartz theme, sober"). Font-family + accent-color + a matching border
 * radius is deliberately as far as this goes - AG Grid 35's own theming API isn't fought further.
 */
export const dataGridTheme = themeQuartz.withParams({
  accentColor: "#0e7490",
  fontFamily: "'Inter Variable', system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif",
  headerFontWeight: 600,
  spacing: 7,
  wrapperBorderRadius: "8px",
});

// Not generic over TData (a plain `ColDef<any>` literal) - passed as the first, lower-priority
// defaultColDef prop below so a caller-supplied defaultColDef (which lands in `...rest`, spread
// after) can override it. Keeping this untyped-to-TData avoids a TS generic-spread limitation:
// merging two independently-generic ColDef<TData> objects at the value level doesn't type-check
// cleanly (the merged literal's `field` widens to `string`, which no longer satisfies
// `ColDefField<TData>` for an unconstrained TData).
const baseColDef: ColDef = {
  sortable: true,
  filter: true,
  resizable: true,
};

export interface DataGridProps<TData> extends Omit<AgGridReactProps<TData>, "theme" | "rowData" | "columnDefs"> {
  rowData: TData[] | undefined;
  columnDefs: ColDef<TData>[];
  /** Fixed pixel height for the grid's scroll viewport (AG Grid requires an explicit-height
   *  container unless domLayout="autoHeight"). Defaults to a reasonable list-view height. */
  height?: number;
  onRowClicked?: (event: RowClickedEvent<TData>) => void;
}

/**
 * Thin reusable wrapper around AgGridReact (components/DataGrid.tsx, M4 brief item 1) — pins the
 * Quartz theme + Community module registration + sensible defaults (sortable/filterable/resizable
 * columns, Swedish "no rows" overlay) in one place so later milestones (Resultatvy, Planeringskarta,
 * ...) don't each re-derive the AG Grid setup boilerplate.
 */
export function DataGrid<TData>({ rowData, columnDefs, height = 520, ...rest }: DataGridProps<TData>) {
  return (
    <Box style={{ height, width: "100%" }}>
      <AgGridReact<TData>
        theme={dataGridTheme}
        rowData={rowData ?? []}
        columnDefs={columnDefs}
        defaultColDef={baseColDef}
        overlayNoRowsTemplate={`<span>${sv.dataGrid.noRows}</span>`}
        animateRows
        {...rest}
      />
    </Box>
  );
}
