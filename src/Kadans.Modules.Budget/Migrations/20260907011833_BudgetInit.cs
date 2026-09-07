using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Budget.Migrations
{
    /// <inheritdoc />
    public partial class BudgetInit : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.EnsureSchema(
                name: "budget");

            migrationBuilder.CreateTable(
                name: "accounts",
                schema: "budget",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<string>(type: "character varying(450)", maxLength: 450, nullable: false),
                    name = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    currency = table.Column<string>(type: "character varying(8)", maxLength: 8, nullable: false),
                    type = table.Column<string>(type: "character varying(32)", maxLength: 32, nullable: false),
                    initial_balance = table.Column<decimal>(type: "numeric(16,2)", precision: 16, scale: 2, nullable: false),
                    is_archived = table.Column<bool>(type: "boolean", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_accounts", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "categories",
                schema: "budget",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<string>(type: "character varying(450)", maxLength: 450, nullable: false),
                    name = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    kind = table.Column<string>(type: "character varying(16)", maxLength: 16, nullable: false),
                    icon = table.Column<string>(type: "character varying(16)", maxLength: 16, nullable: true),
                    is_archived = table.Column<bool>(type: "boolean", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_categories", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "category_budgets",
                schema: "budget",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<string>(type: "character varying(450)", maxLength: 450, nullable: false),
                    category_id = table.Column<Guid>(type: "uuid", nullable: false),
                    currency = table.Column<string>(type: "character varying(8)", maxLength: 8, nullable: false),
                    monthly_limit = table.Column<decimal>(type: "numeric(16,2)", precision: 16, scale: 2, nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_category_budgets", x => x.id);
                    table.ForeignKey(
                        name: "fk_category_budgets_categories_category_id",
                        column: x => x.category_id,
                        principalSchema: "budget",
                        principalTable: "categories",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "recurring_transactions",
                schema: "budget",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<string>(type: "character varying(450)", maxLength: 450, nullable: false),
                    account_id = table.Column<Guid>(type: "uuid", nullable: false),
                    kind = table.Column<string>(type: "character varying(16)", maxLength: 16, nullable: false),
                    amount = table.Column<decimal>(type: "numeric(16,2)", precision: 16, scale: 2, nullable: false),
                    currency = table.Column<string>(type: "character varying(8)", maxLength: 8, nullable: false),
                    category_id = table.Column<Guid>(type: "uuid", nullable: true),
                    note = table.Column<string>(type: "character varying(1000)", maxLength: 1000, nullable: false),
                    rrule = table.Column<string>(type: "character varying(512)", maxLength: 512, nullable: false),
                    time_zone_id = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: false),
                    start_date = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    generated_through = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    is_active = table.Column<bool>(type: "boolean", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_recurring_transactions", x => x.id);
                    table.ForeignKey(
                        name: "fk_recurring_transactions_accounts_account_id",
                        column: x => x.account_id,
                        principalSchema: "budget",
                        principalTable: "accounts",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "fk_recurring_transactions_categories_category_id",
                        column: x => x.category_id,
                        principalSchema: "budget",
                        principalTable: "categories",
                        principalColumn: "id",
                        onDelete: ReferentialAction.SetNull);
                });

            migrationBuilder.CreateTable(
                name: "transactions",
                schema: "budget",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<string>(type: "character varying(450)", maxLength: 450, nullable: false),
                    account_id = table.Column<Guid>(type: "uuid", nullable: false),
                    kind = table.Column<string>(type: "character varying(16)", maxLength: 16, nullable: false),
                    amount = table.Column<decimal>(type: "numeric(16,2)", precision: 16, scale: 2, nullable: false),
                    currency = table.Column<string>(type: "character varying(8)", maxLength: 8, nullable: false),
                    occurred_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    category_id = table.Column<Guid>(type: "uuid", nullable: true),
                    note = table.Column<string>(type: "character varying(1000)", maxLength: 1000, nullable: false),
                    transfer_account_id = table.Column<Guid>(type: "uuid", nullable: true),
                    transfer_amount = table.Column<decimal>(type: "numeric(16,2)", precision: 16, scale: 2, nullable: true),
                    recurring_transaction_id = table.Column<Guid>(type: "uuid", nullable: true),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_transactions", x => x.id);
                    table.ForeignKey(
                        name: "fk_transactions_accounts_account_id",
                        column: x => x.account_id,
                        principalSchema: "budget",
                        principalTable: "accounts",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "fk_transactions_accounts_transfer_account_id",
                        column: x => x.transfer_account_id,
                        principalSchema: "budget",
                        principalTable: "accounts",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "fk_transactions_categories_category_id",
                        column: x => x.category_id,
                        principalSchema: "budget",
                        principalTable: "categories",
                        principalColumn: "id",
                        onDelete: ReferentialAction.SetNull);
                });

            migrationBuilder.CreateIndex(
                name: "IX_accounts_user_id_is_archived",
                schema: "budget",
                table: "accounts",
                columns: new[] { "user_id", "is_archived" });

            migrationBuilder.CreateIndex(
                name: "IX_categories_user_id_kind",
                schema: "budget",
                table: "categories",
                columns: new[] { "user_id", "kind" });

            migrationBuilder.CreateIndex(
                name: "IX_category_budgets_category_id",
                schema: "budget",
                table: "category_budgets",
                column: "category_id");

            migrationBuilder.CreateIndex(
                name: "IX_category_budgets_user_id_category_id",
                schema: "budget",
                table: "category_budgets",
                columns: new[] { "user_id", "category_id" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_recurring_transactions_account_id",
                schema: "budget",
                table: "recurring_transactions",
                column: "account_id");

            migrationBuilder.CreateIndex(
                name: "IX_recurring_transactions_category_id",
                schema: "budget",
                table: "recurring_transactions",
                column: "category_id");

            migrationBuilder.CreateIndex(
                name: "IX_recurring_transactions_generated_through",
                schema: "budget",
                table: "recurring_transactions",
                column: "generated_through",
                filter: "is_active = true");

            migrationBuilder.CreateIndex(
                name: "IX_transactions_account_id",
                schema: "budget",
                table: "transactions",
                column: "account_id");

            migrationBuilder.CreateIndex(
                name: "IX_transactions_category_id",
                schema: "budget",
                table: "transactions",
                column: "category_id");

            migrationBuilder.CreateIndex(
                name: "IX_transactions_transfer_account_id",
                schema: "budget",
                table: "transactions",
                column: "transfer_account_id");

            migrationBuilder.CreateIndex(
                name: "ix_transactions_user_account_occurred_desc",
                schema: "budget",
                table: "transactions",
                columns: new[] { "user_id", "account_id", "occurred_at" },
                descending: new[] { false, false, true });

            migrationBuilder.CreateIndex(
                name: "ix_transactions_user_occurred_desc",
                schema: "budget",
                table: "transactions",
                columns: new[] { "user_id", "occurred_at" },
                descending: new[] { false, true });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "category_budgets",
                schema: "budget");

            migrationBuilder.DropTable(
                name: "recurring_transactions",
                schema: "budget");

            migrationBuilder.DropTable(
                name: "transactions",
                schema: "budget");

            migrationBuilder.DropTable(
                name: "accounts",
                schema: "budget");

            migrationBuilder.DropTable(
                name: "categories",
                schema: "budget");
        }
    }
}
