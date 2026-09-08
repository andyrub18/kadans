using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Budget.Migrations
{
    /// <inheritdoc />
    public partial class BudgetMultiCurrency : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "currency_rates",
                schema: "budget",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<string>(type: "character varying(450)", maxLength: 450, nullable: false),
                    currency = table.Column<string>(type: "character varying(8)", maxLength: 8, nullable: false),
                    rate_in_base = table.Column<decimal>(type: "numeric(14,6)", precision: 14, scale: 6, nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_currency_rates", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "profiles",
                schema: "budget",
                columns: table => new
                {
                    user_id = table.Column<string>(type: "character varying(450)", maxLength: 450, nullable: false),
                    base_currency = table.Column<string>(type: "character varying(8)", maxLength: 8, nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_profiles", x => x.user_id);
                });

            migrationBuilder.CreateIndex(
                name: "IX_currency_rates_user_id_currency",
                schema: "budget",
                table: "currency_rates",
                columns: new[] { "user_id", "currency" },
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "currency_rates",
                schema: "budget");

            migrationBuilder.DropTable(
                name: "profiles",
                schema: "budget");
        }
    }
}
